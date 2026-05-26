// Package defense is the Go mirror of evolia-security's evolutive defense: the
// more hostile input a service absorbs, the harder it defends.
//
// AdaptiveDefense is a bounded, concurrency-safe ring of recent attacks — the
// "mémoire tampon en son sein" — whose level is the accumulated severity it
// holds and decays as quiet ticks pass. LooksLikeInjection flags SQLi-like
// control inputs. Strictly reactive: detect, reject, harden, record — never
// retaliate.
package defense

import (
	"strings"
	"sync"
	"time"
)

// AttackKind classifies absorbed hostile input; severity feeds the level.
type AttackKind int

const (
	SQLInjection AttackKind = iota
	BadSignature
	Unauthorized
	Malformed
	ForgedWork
)

func (k AttackKind) severity() float64 {
	switch k {
	case SQLInjection:
		return 1.0
	case ForgedWork:
		return 1.0
	case BadSignature:
		return 0.8
	case Unauthorized:
		return 0.6
	case Malformed:
		return 0.3
	default:
		return 0.0
	}
}

// AdaptiveDefense is a bounded ring of recent attack severities.
type AdaptiveDefense struct {
	mu       sync.Mutex
	capacity int
	buffer   []float64
}

func New(capacity int) *AdaptiveDefense {
	if capacity < 1 {
		capacity = 1
	}
	return &AdaptiveDefense{capacity: capacity}
}

// Record absorbs an attack, evicting the oldest when full, and returns the new
// defense level.
func (d *AdaptiveDefense) Record(kind AttackKind) float64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.buffer) == d.capacity {
		d.buffer = d.buffer[1:]
	}
	d.buffer = append(d.buffer, kind.severity())
	return d.sum()
}

// Level is the accumulated severity of buffered attacks: the more evolIA has
// absorbed, the higher (and the stricter callers should be).
func (d *AdaptiveDefense) Level() float64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.sum()
}

// Decay forgets the oldest buffered attack — call on a quiet tick so the
// defense relaxes when the pressure stops.
func (d *AdaptiveDefense) Decay() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.buffer) > 0 {
		d.buffer = d.buffer[1:]
	}
}

// Len is the number of buffered attacks.
func (d *AdaptiveDefense) Len() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.buffer)
}

func (d *AdaptiveDefense) sum() float64 {
	var total float64
	for _, s := range d.buffer {
		total += s
	}
	return total
}

// --- evolutive intensity coupling + admission throttle -----------------------
//
// NetIntensity is the operational instantiation of evolia-security's a_global:
//
//	I = A_evo + P_free − D_evo
//
// evaluated on the services' live signals instead of the integral over assumed
// dynamics. A_evo is the recent attack pressure, P_free the passive propagation
// (mesh activity), D_evo the absorbed defense (the buffer Level). Because D_evo
// subtracts, the net intensity falls as evolIA absorbs more — it "wins" the more
// it is attacked. Positive and rising means pressure currently outpaces defense.
func NetIntensity(aEvo, pFree, dEvo float64) float64 {
	return aEvo + pFree - dEvo
}

const (
	// pressureSaturation is the absorbed-defense level at which the admission
	// throttle reaches its floor; beyond it there is no further tightening.
	pressureSaturation = 10.0

	admitMaxBurst   = 20.0 // per-source tokens at rest (calm)
	admitFloorBurst = 2.0  // guaranteed burst even at full pressure
	admitMaxRate    = 10.0 // tokens/sec at rest
	admitFloorRate  = 1.0  // tokens/sec at full pressure

	gateMaxSources = 1024 // bound the per-source table against source churn
)

// Pressure maps an absorbed-defense level to a 0..1 throttle pressure: 0 when
// calm, 1 once the level saturates. Continuous, so the throttle cannot flap.
func Pressure(level float64) float64 {
	p := level / pressureSaturation
	if p < 0 {
		return 0
	}
	if p > 1 {
		return 1
	}
	return p
}

// ceilingFloorFrac is the fraction of the proof-of-work growth headroom still
// admitted at full defense pressure: even saturated, evolIA keeps a floor so an
// honest peer's small bounded increment can still land. Mirrors the Gate's
// admitFloor* — the throttle never shuts fully.
const ceilingFloorFrac = 0.25

// CeilingFactor is the PoW arm of the evolutive coupling. It maps the absorbed-
// defense level to a [ceilingFloorFrac, 1] multiplier the value validator applies
// to a claim's growth headroom: calm (level 0) admits the full physical ceiling;
// as the buffer fills with absorbed attacks (ForgedWork included) the admissible
// value envelope contracts toward the floor, so the more forged-work pressure the
// fleet absorbs the less value any block may assert. The same D_evo counterweight
// that shrinks the admission Gate now also tightens what value a block may claim,
// and it breathes back to 1 as the buffer decays. This is the operational form of
// evolia-security::evolutive::ceiling_factor.
func CeilingFactor(level float64) float64 {
	return 1 - Pressure(level)*(1-ceilingFloorFrac)
}

// Gate is a per-source admission throttle whose burst and refill shrink as the
// absorbed defense rises, so a sustained flood is squeezed toward a guaranteed
// floor while a slow legitimate peer still passes. Throttling is pure
// non-action — a denied datagram is dropped, never answered or retaliated
// against — and it relaxes automatically as the defense buffer decays. Per-IP
// limiting carries the usual source-spoofing caveat; the floor stays small.
type Gate struct {
	def *AdaptiveDefense
	now func() time.Time

	mu      sync.Mutex
	buckets map[string]*tokenBucket
}

type tokenBucket struct {
	tokens float64
	last   time.Time
}

// NewGate builds a throttle bound to def's live level. now defaults to
// time.Now; tests inject a clock to advance refills deterministically.
func NewGate(def *AdaptiveDefense, now func() time.Time) *Gate {
	if def == nil {
		def = New(64)
	}
	if now == nil {
		now = time.Now
	}
	return &Gate{def: def, now: now, buckets: make(map[string]*tokenBucket)}
}

// Allow reports whether input from src may be admitted now, consuming one token
// when it can. Under rising defense pressure the per-source burst and refill
// rate shrink toward the floor, throttling a flood while sparing slow peers.
func (g *Gate) Allow(src string) bool {
	p := Pressure(g.def.Level())
	burst := admitMaxBurst - p*(admitMaxBurst-admitFloorBurst)
	rate := admitMaxRate - p*(admitMaxRate-admitFloorRate)

	g.mu.Lock()
	defer g.mu.Unlock()
	now := g.now()
	b := g.buckets[src]
	if b == nil {
		// Bound the table so source churn cannot exhaust memory; evict an
		// arbitrary entry when full (its bucket simply resets when next seen).
		if len(g.buckets) >= gateMaxSources {
			for k := range g.buckets {
				delete(g.buckets, k)
				break
			}
		}
		b = &tokenBucket{tokens: burst, last: now}
		g.buckets[src] = b
	} else if elapsed := now.Sub(b.last).Seconds(); elapsed > 0 {
		b.tokens += elapsed * rate
		b.last = now
	}
	if b.tokens > burst { // clamp when rising pressure has shrunk the burst
		b.tokens = burst
	}
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}

// LooksLikeInjection flags SQL-injection-like payloads on control inputs
// (device ids, peer fields). A positive result means: reject and Record.
func LooksLikeInjection(input string) bool {
	if strings.ContainsRune(input, 0) {
		return true
	}
	lower := strings.ToLower(input)
	needles := []string{
		"' or ", "\" or ", " or 1=1", "'='",
		"--", "/*", "*/", "; drop ", " union select", "xp_cmdshell", "';",
	}
	for _, n := range needles {
		if strings.Contains(lower, n) {
			return true
		}
	}
	return false
}
