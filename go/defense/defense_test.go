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
	if f := CeilingFactor(0); f != 1.0 {
		t.Fatalf("calm factor must be 1.0, got %v", f)
	}
	mid := CeilingFactor(pressureSaturation / 2)
	if !(mid < 1.0 && mid > ceilingFloorFrac) {
		t.Fatalf("mid pressure factor must be between floor and 1, got %v", mid)
	}
	if f := CeilingFactor(pressureSaturation); f != ceilingFloorFrac {
		t.Fatalf("saturated factor must be the floor %v, got %v", ceilingFloorFrac, f)
	}
	if f := CeilingFactor(pressureSaturation * 10); f != ceilingFloorFrac {
		t.Fatalf("beyond saturation the factor must hold at the floor, got %v", f)
	}
}

// countAdmitted floods src at a frozen instant (no refill) and returns how many
// datagrams the gate admitted before throttling — i.e. the current burst.
func countAdmitted(g *Gate, src string) int {
	admitted := 0
	for i := 0; i < 100; i++ {
		if g.Allow(src) {
			admitted++
		}
	}
	return admitted
}

func TestGateCalmAdmitsThenThrottlesAtBurst(t *testing.T) {
	clk := time.Unix(0, 0)
	g := NewGate(New(64), func() time.Time { return clk })
	if got := countAdmitted(g, "1.2.3.4"); got != int(admitMaxBurst) {
		t.Fatalf("calm burst = %d, want %d", got, int(admitMaxBurst))
	}
}

func TestGateSustainedAttackTightensToFloor(t *testing.T) {
	clk := time.Unix(0, 0)
	def := New(64)
	g := NewGate(def, func() time.Time { return clk })

	// (b) A sustained burst of severe attacks drives the level to saturation, so
	// a flooding source is squeezed down to the guaranteed floor.
	for i := 0; i < int(pressureSaturation)+2; i++ {
		def.Record(SQLInjection)
	}
	if got := countAdmitted(g, "9.9.9.9"); got != int(admitFloorBurst) {
		t.Fatalf("under sustained attack burst = %d, want floor %d", got, int(admitFloorBurst))
	}
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

func TestInjectionDetector(t *testing.T) {
	positives := []string{
		"' OR '1'='1",
		"'; DROP TABLE peers;--",
		"a UNION SELECT password FROM users",
	}
	for _, s := range positives {
		if !LooksLikeInjection(s) {
			t.Fatalf("expected injection flagged: %q", s)
		}
	}
	negatives := []string{"phone-galaxy-a52", "owner", "evolia-node"}
	for _, s := range negatives {
		if LooksLikeInjection(s) {
			t.Fatalf("expected clean input: %q", s)
		}
	}
}
