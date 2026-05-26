package pow

import (
	"errors"
	"math"
	"testing"
)

// noCeiling disables the absolute value ceiling for tests that exercise only the
// per-increment work checks (a configured fleet supplies a finite ceiling).
const noCeiling = math.MaxFloat64

// gainOf is the formula a producer applies, used to build honest proofs.
func gainOf(actions map[string]int, v float64) float64 {
	base := 0.0
	for k, c := range actions {
		base += ActionRates[k] * float64(c)
	}
	return base*(1.0+v) + SensorFloor*v
}

func TestValidateAcceptsHonestWork(t *testing.T) {
	actions := map[string]int{"photo_taken": 2, "screen_input": 10}
	v := 0.4
	dt := 5.0
	prior := 100.0
	newV := prior + gainOf(actions, v)

	p := WorkProof{VPrev: prior, Actions: actions, V: v, Dt: dt}
	if err := ValidateBlock(prior, newV, p, noCeiling); err != nil {
		t.Fatalf("honest work must validate, got %v", err)
	}
}

func TestValidateRejectsFabricatedValue(t *testing.T) {
	// Claim a large value with a tiny bit of declared work: must not reconcile.
	actions := map[string]int{"screen_input": 1}
	p := WorkProof{VPrev: 0, Actions: actions, V: 0.1, Dt: 5.0}
	if err := ValidateBlock(0, 1_000_000, p, noCeiling); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("fabricated value must be forged, got %v", err)
	}
}

func TestValidateRejectsImpossibleActionRate(t *testing.T) {
	// 1000 videos in 5s is physically impossible (cap is 2/s -> 10 max), even
	// though the value would reconcile with the declared work.
	actions := map[string]int{"video_taken": 1000}
	v := 0.0
	dt := 5.0
	newV := gainOf(actions, v) // self-consistent, but the rate is absurd
	p := WorkProof{VPrev: 0, Actions: actions, V: v, Dt: dt}
	if err := ValidateBlock(0, newV, p, noCeiling); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("impossible action rate must be forged, got %v", err)
	}
}

func TestValidateRejectsUnknownActionAndBadV(t *testing.T) {
	if err := ValidateBlock(0, 5, WorkProof{VPrev: 0, Actions: map[string]int{"mining": 1}, V: 0.1, Dt: 5}, noCeiling); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("unknown action must be forged, got %v", err)
	}
	actions := map[string]int{"photo_taken": 1}
	if err := ValidateBlock(0, gainOf(actions, 0.5), WorkProof{VPrev: 0, Actions: actions, V: 1.5, Dt: 5}, noCeiling); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("v out of [0,1] must be forged, got %v", err)
	}
}

func TestValidateStaleAndRollback(t *testing.T) {
	// A claim that does not advance our record is stale (skip, not an attack).
	if err := ValidateBlock(50, 50, WorkProof{VPrev: 50, Dt: 5}, noCeiling); !errors.Is(err, ErrStale) {
		t.Fatalf("non-advancing value must be stale, got %v", err)
	}
	if err := ValidateBlock(50, 40, WorkProof{VPrev: 40, Dt: 5}, noCeiling); !errors.Is(err, ErrStale) {
		t.Fatalf("a rollback must be stale, got %v", err)
	}
}

func TestValidateRejectsPriorBelowStored(t *testing.T) {
	// Claiming a prior below what we already validated is a forgery attempt.
	actions := map[string]int{"photo_taken": 1}
	newV := 100 + gainOf(actions, 0.2)
	p := WorkProof{VPrev: 100, Actions: actions, V: 0.2, Dt: 5}
	if err := ValidateBlock(200, newV, p, noCeiling); !errors.Is(err, ErrForgedWork) && !errors.Is(err, ErrStale) {
		// stored=200, newV<200 -> stale is also acceptable here; the point is it
		// must never be accepted.
		t.Fatalf("prior below stored must not be accepted, got %v", err)
	}
}

func TestValidateToleratesDroppedDatagram(t *testing.T) {
	// We hold 100; a datagram was lost so the producer has moved on to prior 110
	// and now claims 110 + one honest cycle. The self-contained proof validates
	// and we catch up to the new value despite the gap.
	actions := map[string]int{"sms_sent": 1}
	v := 0.3
	newV := 110 + gainOf(actions, v)
	p := WorkProof{VPrev: 110, Actions: actions, V: v, Dt: 5}
	if err := ValidateBlock(100, newV, p, noCeiling); err != nil {
		t.Fatalf("a self-contained proof must validate across a gap, got %v", err)
	}
}

func TestMaxGainPerSecMatchesRateCaps(t *testing.T) {
	// 20·0.05 + 5·1.20 + 5·2.50 + 2·8.00 = 35.5 base/sec; gain = 35.5·2 + 1 = 72.
	if got := MaxGainPerSec(); math.Abs(got-72.0) > 1e-9 {
		t.Fatalf("MaxGainPerSec want 72.0, got %v", got)
	}
}

func TestValueCeilingBoundsBaseline(t *testing.T) {
	// A device cannot assert more value than the fleet could have earned since
	// genesis: an inflated baseline above the ceiling is forged even though the
	// declared increment itself reconciles.
	elapsed := 60.0 // one minute since genesis
	ceiling := ValueCeiling(elapsed)
	// ceiling = 72·(60+300) = 25920; a 1e6 baseline is far above it.
	actions := map[string]int{"screen_input": 1}
	p := WorkProof{VPrev: 1_000_000, Actions: actions, V: 0, Dt: 5}
	newV := 1_000_000 + gainOf(actions, 0)
	if err := ValidateBlock(0, newV, p, ceiling); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("baseline above the wall-clock ceiling must be forged, got %v", err)
	}
	// A modest honest first claim under the ceiling is accepted.
	honest := WorkProof{VPrev: 0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5}
	if err := ValidateBlock(0, gainOf(honest.Actions, 0), honest, ceiling); err != nil {
		t.Fatalf("honest claim under the ceiling must validate, got %v", err)
	}
}

func TestValueCeilingFloorsAtHeadroom(t *testing.T) {
	// A fleet born this instant (elapsed 0) still has a finite bound: one
	// headroom window of max accrual. Negative elapsed (skew) clamps to it too,
	// never +Inf — so the genesis instant is not an unbounded hole.
	floor := MaxGainPerSec() * ClockSkewHeadroom
	if c := ValueCeiling(0); math.Abs(c-floor) > 1e-9 {
		t.Fatalf("zero elapsed must floor at %v, got %v", floor, c)
	}
	if c := ValueCeiling(-10); math.Abs(c-floor) > 1e-9 {
		t.Fatalf("negative elapsed must clamp to the floor %v, got %v", floor, c)
	}
}
