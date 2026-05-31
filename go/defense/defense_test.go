package defense

import (
	"testing"
	"time"
)

func TestDefenseGrowsAndIsBounded(t *testing.T) {
	d := New(3)
	if d.Level() != 0 {
		t.Fatalf("fresh defense must be 0, got %v", d.Level())
	}
	l1 := d.Record(Malformed)
	l2 := d.Record(SQLInjection)
	if l2 <= l1 {
		t.Fatalf("absorbing more attacks must raise the level: %v -> %v", l1, l2)
	}
	d.Record(BadSignature)
	d.Record(Unauthorized)
	if d.Len() != 3 {
		t.Fatalf("buffer must stay bounded at 3, got %d", d.Len())
	}
}

func TestDefenseRelaxesOnDecay(t *testing.T) {
	d := New(8)
	d.Record(SQLInjection)
	d.Record(SQLInjection)
	before := d.Level()
	d.Decay()
	if d.Level() >= before {
		t.Fatalf("decay must relax the defense: before=%v after=%v", before, d.Level())
	}
}

func TestNetIntensityDefenseSubtracts(t *testing.T) {
	// D_evo subtracts: more absorbed defense lowers the net intensity, so evolIA
	// "wins" the more it is attacked.
	hi := NetIntensity(5, 1, 2)
	lo := NetIntensity(5, 1, 4)
	if !(lo < hi) {
		t.Fatalf("more defense must lower net intensity: %v !< %v", lo, hi)
	}
}

func TestCeilingFactorTightensWithDefense(t *testing.T) {
	// Calm admits the full physical value ceiling (factor 1); as absorbed
	// attacks accumulate the factor falls monotonically toward the floor, and
	// once the level saturates it holds exactly at the floor — the PoW arm of the
	// same D_evo counterweight that shrinks the admission Gate.
	calm := CeilingFactor(0)
	if calm != 1.0 {
		t.Fatalf("calm ceiling factor must be 1.0, got %v", calm)
	}
	saturated := CeilingFactor(pressureSaturation + 10)
	if saturated < ceilingFloorFrac || saturated > ceilingFloorFrac+0.01 {
		t.Fatalf("saturated ceiling factor must be ~%.2f, got %v", ceilingFloorFrac, saturated)
	}
	if saturated >= calm {
		t.Fatalf("ceiling factor must tighten as defense rises: saturated=%v not < calm=%v", saturated, calm)
	}
}

func TestPressureMapsLevelTo01(t *testing.T) {
	cases := []struct {
		level  float64
		wantMin, wantMax float64
	}{
		{-5, 0, 0},                                        // negative clamps to 0
		{0, 0, 0},                                         // calm
		{pressureSaturation / 2, 0.49, 0.51},             // midway
		{pressureSaturation, 0.99, 1.01},                // saturation
		{pressureSaturation + 1000, 0.99, 1.01},         // beyond saturation clamps to 1
	}
	for i, tc := range cases {
		got := Pressure(tc.level)
		if got < tc.wantMin || got > tc.wantMax {
			t.Fatalf("case %d: Pressure(%.1f) = %v, want [%v, %v]", i, tc.level, got, tc.wantMin, tc.wantMax)
		}
	}
}

func TestAdaptiveCycleStretches(t *testing.T) {
	base := 5 * time.Second
	cases := []struct {
		level       float64
		wantMinMs, wantMaxMs int64
	}{
		{0, 4999, 5001},                                    // calm = base
		{pressureSaturation / 2, 7400, 7600},             // 50% pressure ≈ 1.5×
		{pressureSaturation, 9900, 10100},                // full pressure = 2×
		{pressureSaturation + 1000, 9900, 10100},         // beyond saturation still 2×
	}
	for i, tc := range cases {
		got := AdaptiveCycle(base, tc.level)
		ms := got.Milliseconds()
		if ms < tc.wantMinMs || ms > tc.wantMaxMs {
			t.Fatalf("case %d: AdaptiveCycle(5s, %.1f) = %d ms, want [%d, %d]", i, tc.level, ms, tc.wantMinMs, tc.wantMaxMs)
		}
	}
}

func TestAdaptiveCycleMonotonic(t *testing.T) {
	base := 5 * time.Second
	levels := []float64{0, 1, 2, 5, 10, 20}
	var prev time.Duration
	for _, level := range levels {
		current := AdaptiveCycle(base, level)
		if current < prev {
			t.Fatalf("cycle not monotonic: at level %.1f got %v < prev %v", level, current, prev)
		}
		prev = current
	}
}

func TestGateAllowsUnderCalm(t *testing.T) {
	def := New(64)
	g := NewGate(def, time.Now)
	for i := 0; i < 25; i++ {
		if !g.Allow("1.1.1.1") {
			t.Fatalf("gate should allow source under calm (admission %d)", i+1)
		}
		if !g.Allow("2.2.2.2") {
			t.Fatalf("gate should allow new source under calm (attempt %d)", i+1)
		}
	}
}

func countAdmitted(g *Gate, src string) int {
	count := 0
	for g.Allow(src) {
		count++
	}
	return count
}

func TestGateRisingPressureClampsHoardedTokens(t *testing.T) {
	clk := time.Unix(0, 0)
	def := New(64)
	g := NewGate(def, func() time.Time { return clk })

	// A calm source hoards a big burst...
	g.Allow("5.5.5.5")
	// ...then attacks saturate the defense: its hoarded tokens must be clamped to
	// the floor, so it no longer gets a free flood.
	for i := 0; i < int(pressureSaturation)+2; i++ {
		def.Record(SQLInjection)
	}
	if got := countAdmitted(g, "5.5.5.5"); got != int(admitFloorBurst) {
		t.Fatalf("clamped burst = %d, want floor %d", got, int(admitFloorBurst))
	}
}

func TestGateRelaxesAfterDecay(t *testing.T) {
	clk := time.Unix(0, 0)
	def := New(64)
	g := NewGate(def, func() time.Time { return clk })
	for i := 0; i < int(pressureSaturation)+2; i++ {
		def.Record(SQLInjection)
	}
	// (c) Once the pressure stops and the buffer decays back to calm, the gate
	// reopens to the full burst.
	for def.Level() > 0 {
		def.Decay()
	}
	if got := countAdmitted(g, "7.7.7.7"); got != int(admitMaxBurst) {
		t.Fatalf("after decay burst = %d, want %d", got, int(admitMaxBurst))
	}
}

func TestGateRefillsOverTime(t *testing.T) {
	clk := time.Unix(0, 0)
	g := NewGate(New(64), func() time.Time { return clk })
	// Drain the burst, then let one second pass: the bucket refills at the calm
	// rate so the source is admitted again.
	countAdmitted(g, "3.3.3.3")
	if g.Allow("3.3.3.3") {
		t.Fatal("bucket should be empty right after draining")
	}
	clk = clk.Add(time.Second)
	if !g.Allow("3.3.3.3") {
		t.Fatal("bucket should have refilled after one second")
	}
}

func TestFlowIsolation_DefenseIndependence(t *testing.T) {
	// With flow isolation (Opt 5), blocks and chat have separate defenses.
	// A chat attack must not affect block intake, and vice versa.
	defBlocks := New(64)
	defChat := New(64)

	// Chat accumulates attacks
	for i := 0; i < 5; i++ {
		defChat.Record(SQLInjection)
	}
	chatLevel := defChat.Level()

	// Blocks remain calm
	blockLevel := defBlocks.Level()

	if blockLevel != 0 {
		t.Fatalf("block defense must stay calm while chat is attacked: got %v", blockLevel)
	}
	if chatLevel <= 0 {
		t.Fatalf("chat defense must accumulate: got %v", chatLevel)
	}

	// Cycle stretches by the max (chat's level)
	maxLevel := blockLevel
	if chatLevel > maxLevel {
		maxLevel = chatLevel
	}
	cycle := AdaptiveCycle(5*time.Second, maxLevel)
	if cycle <= 5*time.Second {
		t.Fatalf("cycle must stretch under chat pressure: got %v", cycle)
	}

	// Chat decays independently
	defChat.Decay()
	if defBlocks.Level() != 0 {
		t.Fatal("block defense must not change when chat decays")
	}
	if defChat.Level() >= chatLevel {
		t.Fatalf("chat decay must lower its level: before=%v after=%v", chatLevel, defChat.Level())
	}
}
