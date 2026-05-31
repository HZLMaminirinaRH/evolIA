package egress

import (
	"sync"
	"testing"
	"time"
)

// fakeClock advances under test control so refill behavior is deterministic.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

// advance moves the fake clock forward by d and returns the new time, so
// callers can assert against the post-advance value if they want and the
// helper itself satisfies the 'every function returns a non-null value'
// project rule.
func (c *fakeClock) advance(d time.Duration) time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
	return c.t
}

func TestAllow_FirstCallSucceeds(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	if !l.Allow("peer1") {
		t.Fatal("first send to a new peer must be admitted")
	}
}

func TestAllow_BurstExhausted(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	// burst = 8, so 8 immediate sends succeed, the 9th is throttled.
	for i := 0; i < 8; i++ {
		if !l.Allow("peer1") {
			t.Fatalf("send %d inside the burst budget must pass", i+1)
		}
	}
	if l.Allow("peer1") {
		t.Fatal("the 9th immediate send must be throttled — burst protects the peer")
	}
}

func TestAllow_RefillsAfterTime(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	// Drain the bucket fully.
	for i := 0; i < 8; i++ {
		l.Allow("peer1")
	}
	if l.Allow("peer1") {
		t.Fatal("drained bucket must throttle")
	}
	// 1s elapsed -> +4 tokens (defaultRate=4). 4 sends should pass.
	c.advance(time.Second)
	for i := 0; i < 4; i++ {
		if !l.Allow("peer1") {
			t.Fatalf("after 1s refill, send %d should pass", i+1)
		}
	}
	if l.Allow("peer1") {
		t.Fatal("refill exhausted; further sends must throttle")
	}
}

func TestAllow_PerPeerIsolated(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	// Drain peer1.
	for i := 0; i < 8; i++ {
		l.Allow("peer1")
	}
	if l.Allow("peer1") {
		t.Fatal("peer1 should be drained")
	}
	// peer2 has its own bucket — must not be affected.
	if !l.Allow("peer2") {
		t.Fatal("peer2 must have an independent bucket")
	}
}

func TestAllow_TableBoundedAgainstChurn(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	// Far exceed maxPeers — the table must stay bounded.
	for i := 0; i < defaultMaxPeers*2; i++ {
		l.Allow(testKey(i))
	}
	l.mu.Lock()
	size := len(l.buckets)
	l.mu.Unlock()
	if size > defaultMaxPeers {
		t.Fatalf("table grew beyond cap: got %d, want <= %d", size, defaultMaxPeers)
	}
}

func TestAllow_RefillCapsAtBurst(t *testing.T) {
	c := &fakeClock{t: time.Unix(0, 0)}
	l := NewLimiter(c.now)
	l.Allow("peer1") // create the bucket; one token consumed (7 remain)
	// A huge advance must not let the bucket overflow past burst.
	c.advance(time.Hour)
	for i := 0; i < 8; i++ {
		if !l.Allow("peer1") {
			t.Fatalf("send %d after refill should pass (bucket clamps at burst)", i+1)
		}
	}
	if l.Allow("peer1") {
		t.Fatal("refill must cap at burst — no infinite hoarding from idle time")
	}
}

func testKey(i int) string {
	// Cheap unique key — avoid pulling in strconv for a test helper.
	const hex = "0123456789abcdef"
	out := make([]byte, 0, 8)
	for i != 0 {
		out = append(out, hex[i&0xf])
		i >>= 4
	}
	if len(out) == 0 {
		out = append(out, '0')
	}
	return string(out)
}
