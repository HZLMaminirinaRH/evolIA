// Package pow validates the cognitive proof-of-work backing a mesh value claim.
//
// evolIA's premise is that value (BTC-e) is earned by real digital activity, not
// declared. So a peer's value increment must be the arithmetic image of bounded,
// declared work:
//
//	ΔV = base·(1 + v) + SensorFloor·v,  base = Σ ActionRates[kind]·count
//
// The action component — the main fabrication vector — is fully recomputed and
// rate-capped: you cannot claim more actions than physically possible in the
// elapsed time. The sensor/cognitive multiplier v is trusted but bounded to
// [0,1], so a forged block can inflate the value by at most one plausibly-bounded
// cycle. A claim that does not reconcile is forged and must be rejected (and feed
// the adaptive defense).
//
// Each block is self-contained: it carries its own declared prior value (VPrev),
// so a verifier can validate the producer's increment even across a dropped UDP
// datagram, while the store path enforces monotonicity (value never rolls back)
// against what it already holds.
//
// The constants mirror python/evolia_evolve.py (ACTION_RATES) and the gain
// equation in evolia_value.py (SENSOR_FLOOR). Keep them in sync across languages.
//
// Baseline ceiling (closes the v1 trust-on-first-use hole). A node holding the
// fleet key used to be able to inflate its *baseline* by lying about VPrev on
// first contact. ValidateBlock now also rejects any claim above an absolute
// value ceiling supplied by the caller: the most value the fleet could have
// physically earned since its genesis (MaxGainPerSec · elapsed). Because a real
// device cannot predate the genesis, this bounds even a first-contact baseline
// to a physical limit instead of leaving it unbounded. The caller derives the
// ceiling (see mesh.AdmissibleCeiling) and additionally tightens it under the
// evolutive defense (the more forged-work pressure the fleet absorbs, the lower
// the admissible ceiling) — the PoW arm of evolia-security's a_global D_evo
// counterweight. With no genesis configured the caller disables the ceiling
// (+Inf), preserving standalone behavior. The structural closure of the residual
// baseline-fabrication latitude is on-chain verifiable history, layered on top.
package pow

import (
	"errors"
	"math"
)

// SensorFloor mirrors evolia_value.SENSOR_FLOOR.
const SensorFloor = 1.0

// maxElapsed bounds the work window a single block may claim (seconds). Kept
// generous (one hour) because Android suspends devices aggressively, so a cycle
// that drains a sleep-time backlog still validates rather than being mistaken
// for a forgery; the per-action rate cap remains the real anti-inflation bound.
const maxElapsed = 3600.0

// ActionRates mirrors python/evolia_evolve.py ACTION_RATES: base BTC-e per action.
var ActionRates = map[string]float64{
	"screen_input": 0.05,
	"sms_sent":     1.20,
	"photo_taken":  2.50,
	"video_taken":  8.00,
}

// MaxRatePerSec bounds how many of each action can plausibly occur per second —
// the anti-inflation core. Fabricating value requires fabricating proportional,
// physically-plausible activity, which these caps forbid. Generous, not exact.
var MaxRatePerSec = map[string]float64{
	"screen_input": 20.0,
	"sms_sent":     5.0,
	"photo_taken":  5.0,
	"video_taken":  2.0,
}

// ClockSkewHeadroom (seconds) is added to the elapsed window when sizing the
// value ceiling, so an honest device whose clock leads the verifier's — common
// across mobiles in a fleet — is not falsely rejected at the boundary.
const ClockSkewHeadroom = 300.0

// MaxGainPerSec is the largest value increment one device can earn per second:
// every action performed at its physical rate cap with the cognitive multiplier
// saturated (v=1), gain = base·(1+v) + SensorFloor·v. It is the slope of the
// absolute value ceiling. Derived from the maps so it stays in sync if the rates
// or caps change.
func MaxGainPerSec() float64 {
	var baseMax float64
	for kind, capPerSec := range MaxRatePerSec {
		baseMax += capPerSec * ActionRates[kind]
	}
	const vMax = 1.0
	return baseMax*(1.0+vMax) + SensorFloor*vMax
}

// ValueCeiling is the most value any device could have physically earned in the
// elapsed seconds since the fleet genesis (plus clock-skew headroom). It bounds
// even a trust-on-first-use baseline: a device cannot assert more value than the
// fleet could have generated since it began. Always finite, with a floor of one
// headroom window so a fleet born this instant still has a bound; a negative
// elapsed (clock skew / genesis misconfigured into the future) clamps to that
// floor. Disabling the ceiling entirely (no genesis configured) is the caller's
// concern — see mesh.AdmissibleCeiling.
func ValueCeiling(elapsedSinceGenesis float64) float64 {
	if elapsedSinceGenesis < 0 {
		elapsedSinceGenesis = 0
	}
	return MaxGainPerSec() * (elapsedSinceGenesis + ClockSkewHeadroom)
}

var (
	// ErrStale marks a block whose value does not advance our record (a replay
	// or a reordered datagram). The caller should skip it silently — it is not
	// an attack.
	ErrStale = errors.New("stale value claim")
	// ErrForgedWork marks a value increment not backed by sound, bounded work.
	// The caller should reject it and feed the adaptive defense.
	ErrForgedWork = errors.New("forged cognitive work")
)

// WorkProof is the cognitive work declared to back one value increment, carried
// alongside the value claim on the wire.
type WorkProof struct {
	VPrev   float64        `json:"v_prev"`  // producer's value before this increment
	Actions map[string]int `json:"actions"` // digital actions performed this cycle
	V       float64        `json:"v"`       // sensor/cognitive multiplier, 0..1
	Dt      float64        `json:"dt"`      // elapsed seconds for this increment
}

// Base recomputes the action BTC-e base from declared counts; an unknown kind or
// a negative count is itself a forgery.
func Base(actions map[string]int) (float64, error) {
	var base float64
	for kind, count := range actions {
		rate, ok := ActionRates[kind]
		if !ok {
			return 0, ErrForgedWork
		}
		if count < 0 {
			return 0, ErrForgedWork
		}
		base += rate * float64(count)
	}
	return base, nil
}

// ExpectedGain is the value the formula produces from this work.
func ExpectedGain(p WorkProof) (float64, error) {
	if p.V < 0 || p.V > 1 {
		return 0, ErrForgedWork
	}
	base, err := Base(p.Actions)
	if err != nil {
		return 0, err
	}
	return base*(1.0+p.V) + SensorFloor*p.V, nil
}

// ValidateBlock checks that a peer's claimed new value is backed by the declared
// work, given the value we already hold for that device (storedV, 0 on first
// contact) and an absolute admissible ceiling (the most value the claim may
// assert; pass +Inf to disable). Returns nil to accept, ErrStale to skip, or
// ErrForgedWork to reject and harden the defense.
func ValidateBlock(storedV, newV float64, p WorkProof, ceiling float64) error {
	const eps = 1e-6
	if newV <= storedV+eps {
		return ErrStale // no progress: replay or reorder, not an attack
	}
	if newV > ceiling+eps {
		return ErrForgedWork // exceeds what the fleet could have earned since genesis
	}
	if p.VPrev < storedV-eps {
		return ErrForgedWork // claims a prior below what we have already validated
	}
	if p.Dt <= 0 || p.Dt > maxElapsed {
		return ErrForgedWork
	}
	for kind, count := range p.Actions {
		cap, ok := MaxRatePerSec[kind]
		if !ok || count < 0 {
			return ErrForgedWork
		}
		if float64(count) > cap*p.Dt+eps {
			return ErrForgedWork // more actions than physically possible
		}
	}
	expected, err := ExpectedGain(p)
	if err != nil {
		return err
	}
	delta := newV - p.VPrev
	if delta < -eps {
		return ErrForgedWork
	}
	if math.Abs(delta-expected) > 1e-6*(1+math.Abs(expected)) {
		return ErrForgedWork // the value increment does not match the declared work
	}
	return nil
}
