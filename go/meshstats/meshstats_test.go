package meshstats

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestNewRecorder_AllZero(t *testing.T) {
	r := NewRecorder()
	s := r.Snapshot(0, 0, 0)
	if s.SendsOK != 0 || s.SendsFail != 0 || s.PeersCold != 0 || s.DefenseLevel != 0 {
		t.Fatalf("a fresh recorder must snapshot to zero, got %+v", s)
	}
}

func TestRecord_RoutesEachEventToItsCounter(t *testing.T) {
	cases := []struct {
		name    string
		event   Event
		hits    int
		extract func(Snapshot) uint64
	}{
		{"SendOK", SendOK, 3, func(s Snapshot) uint64 { return s.SendsOK }},
		{"SendFail", SendFail, 2, func(s Snapshot) uint64 { return s.SendsFail }},
		{"EgressThrottled", EgressThrottled, 4, func(s Snapshot) uint64 { return s.ThrottleEvents.Egress }},
		{"DefenseThrottled", DefenseThrottled, 1, func(s Snapshot) uint64 { return s.ThrottleEvents.IngressDefense }},
		{"ColdSkipped", ColdSkipped, 5, func(s Snapshot) uint64 { return s.ThrottleEvents.ColdSkipped }},
		{"BlockReceived", BlockReceived, 7, func(s Snapshot) uint64 { return s.Receives.Blocks }},
		{"ChatReceived", ChatReceived, 2, func(s Snapshot) uint64 { return s.Receives.Chat }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := NewRecorder()
			for i := 0; i < tc.hits; i++ {
				r.Record(tc.event)
			}
			if got := tc.extract(r.Snapshot(0, 0, 0)); got != uint64(tc.hits) {
				t.Fatalf("%s after %d Record calls = %d, want %d", tc.name, tc.hits, got, tc.hits)
			}
		})
	}
}

func TestRecord_UnknownEventIsIgnored(t *testing.T) {
	r := NewRecorder()
	r.Record(Event(9999))
	s := r.Snapshot(0, 0, 0)
	// Every counter must still be zero; an unknown event must not panic and
	// must not silently land on one of the known counters either.
	if s.SendsOK|s.SendsFail|s.ThrottleEvents.Egress|s.ThrottleEvents.IngressDefense|
		s.ThrottleEvents.ColdSkipped|s.Receives.Blocks|s.Receives.Chat != 0 {
		t.Fatalf("unknown event landed on a counter: %+v", s)
	}
}

func TestRecordAttack_RoutesAllBlockKinds(t *testing.T) {
	r := NewRecorder()
	r.RecordAttack(BlockFlow, Injection)
	r.RecordAttack(BlockFlow, BadSignature)
	r.RecordAttack(BlockFlow, BadSignature)
	r.RecordAttack(BlockFlow, ForgedWork)
	r.RecordAttack(BlockFlow, Malformed)
	r.RecordAttack(BlockFlow, Malformed)
	r.RecordAttack(BlockFlow, Malformed)
	b := r.Snapshot(0, 0, 0).AttacksByFlow.Blocks
	if b.Injection != 1 || b.BadSignature != 2 || b.ForgedWork != 1 || b.Malformed != 3 {
		t.Fatalf("block attack routing wrong: got %+v", b)
	}
}

func TestRecordAttack_RoutesTwoChatKinds(t *testing.T) {
	r := NewRecorder()
	r.RecordAttack(ChatFlow, Injection)
	r.RecordAttack(ChatFlow, Malformed)
	r.RecordAttack(ChatFlow, Malformed)
	c := r.Snapshot(0, 0, 0).AttacksByFlow.Chat
	if c.Injection != 1 || c.Malformed != 2 {
		t.Fatalf("chat attack routing wrong: got %+v", c)
	}
}

func TestRecordAttack_ChatIgnoresInapplicableKinds(t *testing.T) {
	r := NewRecorder()
	// Chat envelopes carry no signature and no PoW — these combinations
	// must be silently dropped (caller cannot legitimately produce them).
	r.RecordAttack(ChatFlow, BadSignature)
	r.RecordAttack(ChatFlow, ForgedWork)
	c := r.Snapshot(0, 0, 0).AttacksByFlow.Chat
	if c.Injection|c.Malformed != 0 {
		t.Fatalf("inapplicable chat kinds landed on a counter: %+v", c)
	}
	// And block-flow counters must NOT have been touched either.
	b := r.Snapshot(0, 0, 0).AttacksByFlow.Blocks
	if b.Injection|b.BadSignature|b.ForgedWork|b.Malformed != 0 {
		t.Fatalf("chat-flow RecordAttack leaked into block counters: %+v", b)
	}
}

func TestSnapshot_FoldsExternalDynamicState(t *testing.T) {
	r := NewRecorder()
	s := r.Snapshot(7, 12, 4.5)
	if s.PeersCold != 7 || s.PeersKnown != 12 || s.DefenseLevel != 4.5 {
		t.Fatalf("external dynamic state not folded: got cold=%d known=%d level=%v",
			s.PeersCold, s.PeersKnown, s.DefenseLevel)
	}
}

func TestPersistTo_WritesValidAtomicJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "evolia_mesh_stats.json")

	r := NewRecorder()
	r.Record(SendOK)
	r.Record(SendFail)
	r.RecordAttack(BlockFlow, ForgedWork)
	if err := r.PersistTo(path, 1, 4, 3.2); err != nil {
		t.Fatalf("PersistTo failed: %v", err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("stats file unreadable: %v", err)
	}
	var s Snapshot
	if err := json.Unmarshal(data, &s); err != nil {
		t.Fatalf("stats file is not valid JSON: %v", err)
	}
	if s.SendsOK != 1 || s.SendsFail != 1 {
		t.Fatalf("persisted counters wrong: %+v", s)
	}
	if s.AttacksByFlow.Blocks.ForgedWork != 1 {
		t.Fatalf("persisted attack count wrong: %+v", s.AttacksByFlow.Blocks)
	}
	if s.PeersCold != 1 || s.PeersKnown != 4 || s.DefenseLevel != 3.2 {
		t.Fatalf("persisted dynamic state wrong: cold=%d known=%d level=%v",
			s.PeersCold, s.PeersKnown, s.DefenseLevel)
	}
	if s.UpdatedAt == "" {
		t.Fatal("UpdatedAt must be set on every snapshot")
	}
}

func TestPersistTo_OverwritesPrevious(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "evolia_mesh_stats.json")
	r := NewRecorder()
	r.Record(SendOK)
	if err := r.PersistTo(path, 0, 0, 0); err != nil {
		t.Fatalf("first write failed: %v", err)
	}
	r.Record(SendOK)
	r.Record(SendOK)
	if err := r.PersistTo(path, 0, 0, 0); err != nil {
		t.Fatalf("second write failed: %v", err)
	}
	data, _ := os.ReadFile(path)
	var s Snapshot
	_ = json.Unmarshal(data, &s)
	if s.SendsOK != 3 {
		t.Fatalf("after re-persist SendsOK = %d, want 3 (the latest cumulative)", s.SendsOK)
	}
}
