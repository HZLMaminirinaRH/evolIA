// Package egress is the outbound traffic shaper for evolIA's mesh.
//
// Unlike defense.Gate which reactively throttles hostile INGRESS (attack-driven),
// Limiter is a preventive PER-PEER outbound token bucket: it caps the rate at
// which we send our own datagrams to any single peer, so a fan-out across many
// peers (mesh growth) or a burst of queued messages cannot saturate the local
// radio nor batter a receiver. A throttled send is NOT recorded as an attack
// (there is no hostile signal here — we're protecting ourselves and our peers).
//
// The bucket is keyed by peer host (not host:port) so blocks and chat targeting
// the same device share the same rate budget — what matters is the receiver's
// load, not which port we hit. The table is bounded against peer churn.
package egress

import (
	"sync"
	"time"
)

const (
	// defaultBurst sets how many sends a peer can absorb in one go. Sized so
	// normal cycle traffic (one value block + a few chat envelopes per cycle)
	// fits without throttling, while a 100-message blast is bounded.
	defaultBurst = 8.0

	// defaultRate sets the per-peer refill (tokens/sec). At 4/sec the bucket
	// refills from empty in 2s — comfortably faster than the 5s mesh cycle, so
	// a sustained legitimate sender is never starved.
	defaultRate = 4.0

	// defaultMaxPeers bounds the per-peer table so churn cannot grow it
	// unbounded; matches defense.Gate's source-table cap for symmetry.
	defaultMaxPeers = 1024
)

// Limiter is a concurrency-safe per-peer outbound token bucket.
type Limiter struct {
	burst    float64
	rate     float64
	maxPeers int
	now      func() time.Time

	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	last   time.Time
}

// NewLimiter builds a limiter with default burst/rate. now defaults to
// time.Now; tests inject a clock to advance refills deterministically.
func NewLimiter(now func() time.Time) *Limiter {
	if now == nil {
		now = time.Now
	}
	return &Limiter{
		burst:    defaultBurst,
		rate:     defaultRate,
		maxPeers: defaultMaxPeers,
		now:      now,
		buckets:  make(map[string]*bucket),
	}
}

// Allow reports whether a send to peer may proceed now, consuming one token
// when it can. A denied send is silently dropped by the caller — no attack
// scoring, no retry obligation; the next cycle's refill restores headroom.
func (l *Limiter) Allow(peer string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b := l.buckets[peer]
	if b == nil {
		if len(l.buckets) >= l.maxPeers {
			// Bound the table; evict an arbitrary entry (its bucket simply
			// resets when next seen, so the eviction is harmless).
			for k := range l.buckets {
				delete(l.buckets, k)
				break
			}
		}
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[peer] = b
	} else if elapsed := now.Sub(b.last).Seconds(); elapsed > 0 {
		b.tokens += elapsed * l.rate
		b.last = now
	}
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	if b.tokens >= 1 {
		b.tokens--
		return true
	}
	return false
}
