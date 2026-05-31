package peerhealth

import (
	"sync"
	"testing"
	"time"
)

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func TestMaySend_UnknownPeerIsWarm(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	if !tr.MaySend("192.168.1.50") {
		t.Fatal("an unknown peer must be eligible (warm by default)")
	}
}

func TestRecordFailure_StartsBackoff(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	tr.RecordFailure("192.168.1.50")
	if tr.MaySend("192.168.1.50") {
		t.Fatal("a peer that just failed must be in backoff")
	}
	// 1st failure -> 5s backoff. Advance 4s -> still cold.
	c.advance(4 * time.Second)
	if tr.MaySend("192.168.1.50") {
		t.Fatal("peer must remain cold inside the first backoff window")
	}
	// Pass the 5s mark.
	c.advance(2 * time.Second)
	if !tr.MaySend("192.168.1.50") {
		t.Fatal("peer must be re-warm once the backoff window has elapsed")
	}
}

func TestBackoff_ExponentialUntilCap(t *testing.T) {
	cases := []struct {
		failures int
		want     time.Duration
	}{
		{1, 5 * time.Second},
		{2, 10 * time.Second},
		{3, 20 * time.Second},
		{4, 40 * time.Second},
		{5, 80 * time.Second},
		{6, 160 * time.Second},
		{7, 300 * time.Second},   // first to hit cap
		{20, 300 * time.Second},  // long-cold still capped
		{100, 300 * time.Second}, // pathological count still capped
	}
	for _, tc := range cases {
		if got := backoffFor(tc.failures); got != tc.want {
			t.Fatalf("backoffFor(%d) = %v, want %v", tc.failures, got, tc.want)
		}
	}
}

func TestRecordSuccess_ResetsBackoff(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	// Build up 3 consecutive failures.
	tr.RecordFailure("192.168.1.50")
	tr.RecordFailure("192.168.1.50")
	tr.RecordFailure("192.168.1.50")
	if tr.ConsecutiveFailures("192.168.1.50") != 3 {
		t.Fatalf("expected 3 consecutive failures, got %d", tr.ConsecutiveFailures("192.168.1.50"))
	}
	if tr.MaySend("192.168.1.50") {
		t.Fatal("peer should be cold after 3 failures")
	}
	// Recovery: a single success clears the streak and re-warms immediately.
	tr.RecordSuccess("192.168.1.50")
	if tr.ConsecutiveFailures("192.168.1.50") != 0 {
		t.Fatal("RecordSuccess must reset the consecutive failure count")
	}
	if !tr.MaySend("192.168.1.50") {
		t.Fatal("a peer that just succeeded must be eligible again immediately")
	}
}

func TestColdCount_TracksCurrentlyColdOnly(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	tr.RecordFailure("a")
	tr.RecordFailure("b")
	tr.RecordFailure("c")
	tr.RecordSuccess("c") // c recovers immediately
	if got := tr.ColdCount(); got != 2 {
		t.Fatalf("ColdCount after 3 fails + 1 success should be 2, got %d", got)
	}
	// Let the backoff window pass; everybody re-warms even without an explicit success.
	c.advance(10 * time.Second)
	if got := tr.ColdCount(); got != 0 {
		t.Fatalf("ColdCount after backoff elapsed should be 0, got %d", got)
	}
}

func TestPerPeerIsolation(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	tr.RecordFailure("peerA")
	tr.RecordFailure("peerA")
	if tr.MaySend("peerA") {
		t.Fatal("peerA must be cold")
	}
	// peerB is untouched — fresh peers must not inherit peerA's penalty.
	if !tr.MaySend("peerB") {
		t.Fatal("peerB must be warm — failure tracking is per-peer")
	}
}

func TestTable_BoundedAgainstChurn(t *testing.T) {
	c := &fakeClock{t: time.Unix(1_700_000_000, 0)}
	tr := NewTracker(c.now)
	for i := 0; i < maxPeers*2; i++ {
		tr.RecordFailure(testKey(i))
	}
	tr.mu.Lock()
	size := len(tr.peers)
	tr.mu.Unlock()
	if size > maxPeers {
		t.Fatalf("peer table grew beyond cap: got %d, want <= %d", size, maxPeers)
	}
}

func testKey(i int) string {
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
