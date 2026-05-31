// Package peerhealth tracks the live/dead state of each mesh peer so a dead
// peer (DNS error, network unreachable, write-after-EOF) is skipped during an
// exponential backoff window instead of consuming cycle budget every 5 s.
//
// The signal is SEND-SIDE only — UDP Dial and Write errors. UDP is
// fire-and-forget so this is imperfect: a peer that is silently dropping our
// datagrams (e.g. firewall, app not running but radio up) still looks alive to
// the kernel. Receive-side correlation (a peer that never replies after K
// cycles is suspect) is a Phase-3 extension; this package gives a useful first
// cut by catching the clear-cut failures (wrong IP, network unreachable, host
// down) so they stop wasting cycles.
//
// Strictly separate from defense: a failed send is NOT an attack, the tracker
// never feeds the defense buffer, and a cold peer is dropped silently.
package peerhealth

import (
	"sync"
	"time"
)

const (
	// baseBackoff is the cooldown after the first failure — sized to skip
	// roughly one mesh cycle so a peer that is briefly unreachable still gets
	// a fast retry, not a punitive wait.
	baseBackoff = 5 * time.Second

	// maxBackoff caps the exponential growth so a long-dead peer is still
	// retried every five minutes (it might be a phone that just turned its
	// radio back on).
	maxBackoff = 300 * time.Second

	// maxPeers bounds the per-peer table against churn — matches egress/Gate
	// for symmetry. An evicted entry simply starts warm next time we see it.
	maxPeers = 1024
)

// Tracker is a concurrency-safe per-peer health map.
type Tracker struct {
	now func() time.Time

	mu    sync.Mutex
	peers map[string]*state
}

type state struct {
	consecutiveFailures int
	nextRetryAt         time.Time
	successesTotal      uint64
	failuresTotal       uint64
	lastSuccessAt       time.Time
	lastFailureAt       time.Time
}

// NewTracker builds a tracker. now defaults to time.Now; tests inject a clock
// to advance the backoff window deterministically.
func NewTracker(now func() time.Time) *Tracker {
	if now == nil {
		now = time.Now
	}
	return &Tracker{now: now, peers: make(map[string]*state)}
}

// MaySend reports whether peer is currently eligible — true for an unknown
// peer (warm by default) and for a peer whose backoff window has elapsed.
func (t *Tracker) MaySend(peer string) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	s, ok := t.peers[peer]
	if !ok {
		return true
	}
	return !t.now().Before(s.nextRetryAt)
}

// RecordSuccess clears the backoff window so peer is immediately eligible
// again. A peer that has been cold and then recovers re-warms instantly.
func (t *Tracker) RecordSuccess(peer string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.touchLocked(peer)
	s.consecutiveFailures = 0
	s.nextRetryAt = time.Time{}
	s.successesTotal++
	s.lastSuccessAt = t.now()
}

// RecordFailure increments the consecutive-failure count and schedules the
// next retry per the exponential curve (capped at maxBackoff).
func (t *Tracker) RecordFailure(peer string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	s := t.touchLocked(peer)
	s.consecutiveFailures++
	s.failuresTotal++
	s.lastFailureAt = t.now()
	s.nextRetryAt = s.lastFailureAt.Add(backoffFor(s.consecutiveFailures))
}

// ColdCount reports how many tracked peers are currently in a backoff window,
// for the per-cycle diagnostic log ("we're skipping N dead peers").
func (t *Tracker) ColdCount() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.now()
	var n int
	for _, s := range t.peers {
		if now.Before(s.nextRetryAt) {
			n++
		}
	}
	return n
}

// ConsecutiveFailures returns the current failure streak for peer, so the
// caller can log "peer X cold after N consecutive failures" without exposing
// the internal state. Returns 0 for an unknown peer.
func (t *Tracker) ConsecutiveFailures(peer string) int {
	t.mu.Lock()
	defer t.mu.Unlock()
	if s, ok := t.peers[peer]; ok {
		return s.consecutiveFailures
	}
	return 0
}

func (t *Tracker) touchLocked(peer string) *state {
	if s, ok := t.peers[peer]; ok {
		return s
	}
	if len(t.peers) >= maxPeers {
		for k := range t.peers {
			delete(t.peers, k)
			break
		}
	}
	s := &state{}
	t.peers[peer] = s
	return s
}

// backoffFor maps a failure streak to a cooldown: 1→5s, 2→10s, 3→20s, 4→40s,
// 5→80s, 6+→cap. A success resets the streak to 0, so the curve restarts on
// the next failure.
func backoffFor(failures int) time.Duration {
	if failures <= 0 {
		return 0
	}
	d := baseBackoff
	for i := 1; i < failures; i++ {
		d *= 2
		if d >= maxBackoff {
			return maxBackoff
		}
	}
	return d
}
