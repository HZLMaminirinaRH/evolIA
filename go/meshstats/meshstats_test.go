package meshstats

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNewRecorder_AllZero(t *testing.T) {
	r := NewRecorder()
	s := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second)
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
				got := r.Record(tc.event)
				if got != uint64(i+1) {
					t.Fatalf("%s Record return on call %d: got %d, want %d", tc.name, i+1, got, i+1)
				}
			}
			if got := tc.extract(r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second)); got != uint64(tc.hits) {
				t.Fatalf("%s after %d Record calls = %d, want %d", tc.name, tc.hits, got, tc.hits)
			}
		})
	}
}

func TestRecord_UnknownEventReturnsZero(t *testing.T) {
	r := NewRecorder()
	if got := r.Record(Event(9999)); got != 0 {
		t.Fatalf("an unknown event must return 0 to signal no counter hit, got %d", got)
	}
	s := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second)
	// Every counter must still be zero; an unknown event must not panic and
	// must not silently land on one of the known counters either.
	if s.SendsOK|s.SendsFail|s.ThrottleEvents.Egress|s.ThrottleEvents.IngressDefense|
		s.ThrottleEvents.ColdSkipped|s.Receives.Blocks|s.Receives.Chat != 0 {
		t.Fatalf("unknown event landed on a counter: %+v", s)
	}
}

func TestRecordAttack_RoutesAllBlockKinds(t *testing.T) {
	r := NewRecorder()
	if got := r.RecordAttack(BlockFlow, Injection); got != 1 {
		t.Fatalf("first BlockFlow/Injection must return 1, got %d", got)
	}
	r.RecordAttack(BlockFlow, BadSignature)
	if got := r.RecordAttack(BlockFlow, BadSignature); got != 2 {
		t.Fatalf("second BlockFlow/BadSignature must return 2, got %d", got)
	}
	r.RecordAttack(BlockFlow, ForgedWork)
	r.RecordAttack(BlockFlow, Malformed)
	r.RecordAttack(BlockFlow, Malformed)
	r.RecordAttack(BlockFlow, Malformed)
	b := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second).AttacksByFlow.Blocks
	if b.Injection != 1 || b.BadSignature != 2 || b.ForgedWork != 1 || b.Malformed != 3 {
		t.Fatalf("block attack routing wrong: got %+v", b)
	}
}

func TestRecordAttack_RoutesTwoChatKinds(t *testing.T) {
	r := NewRecorder()
	if got := r.RecordAttack(ChatFlow, Injection); got != 1 {
		t.Fatalf("ChatFlow/Injection must return 1, got %d", got)
	}
	r.RecordAttack(ChatFlow, Malformed)
	if got := r.RecordAttack(ChatFlow, Malformed); got != 2 {
		t.Fatalf("second ChatFlow/Malformed must return 2, got %d", got)
	}
	c := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second).AttacksByFlow.Chat
	if c.Injection != 1 || c.Malformed != 2 {
		t.Fatalf("chat attack routing wrong: got %+v", c)
	}
}

func TestRecordAttack_InapplicableChatComboReturnsZero(t *testing.T) {
	r := NewRecorder()
	// Chat envelopes carry no signature and no PoW — these combinations
	// must be silently dropped AND return 0 to signal "no counter hit".
	if got := r.RecordAttack(ChatFlow, BadSignature); got != 0 {
		t.Fatalf("(ChatFlow, BadSignature) must return 0, got %d", got)
	}
	if got := r.RecordAttack(ChatFlow, ForgedWork); got != 0 {
		t.Fatalf("(ChatFlow, ForgedWork) must return 0, got %d", got)
	}
	c := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second).AttacksByFlow.Chat
	if c.Injection|c.Malformed != 0 {
		t.Fatalf("inapplicable chat kinds landed on a counter: %+v", c)
	}
	b := r.Snapshot(0, 0, 0, 5*time.Second, 5*time.Second).AttacksByFlow.Blocks
	if b.Injection|b.BadSignature|b.ForgedWork|b.Malformed != 0 {
		t.Fatalf("chat-flow RecordAttack leaked into block counters: %+v", b)
	}
}

func TestSnapshot_FoldsExternalDynamicState(t *testing.T) {
	r := NewRecorder()
	s := r.Snapshot(7, 12, 4.5, 5*time.Second, 5*time.Second)
	if s.PeersCold != 7 || s.PeersKnown != 12 || s.DefenseLevel != 4.5 {
		t.Fatalf("external dynamic state not folded: got cold=%d known=%d level=%v",
			s.PeersCold, s.PeersKnown, s.DefenseLevel)
	}
}

func TestSnapshot_SurfacesAdaptiveCycle(t *testing.T) {
	r := NewRecorder()
	// Under pressure the live cycle stretches above base; the snapshot must
	// surface both so the UI can show "we slowed down because we're under
	// pressure" rather than just "we slowed down".
	s := r.Snapshot(0, 0, 0, 5*time.Second, 9*time.Second)
	if s.BaseCycleMs != 5000 {
		t.Fatalf("BaseCycleMs = %d, want 5000", s.BaseCycleMs)
	}
	if s.CycleMs != 9000 {
		t.Fatalf("CycleMs = %d, want 9000", s.CycleMs)
	}
}

func TestPersistTo_WritesValidAtomicJSON(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "evolia_mesh_stats.json")

	r := NewRecorder()
	r.Record(SendOK)
	r.Record(SendFail)
	r.RecordAttack(BlockFlow, ForgedWork)
	if err := r.PersistTo(path, 1, 4, 3.2, 5*time.Second, 5*time.Second); err != nil {
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
	if err := r.PersistTo(path, 0, 0, 0, 5*time.Second, 5*time.Second); err != nil {
		t.Fatalf("first write failed: %v", err)
	}
	r.Record(SendOK)
	r.Record(SendOK)
	if err := r.PersistTo(path, 0, 0, 0, 5*time.Second, 5*time.Second); err != nil {
		t.Fatalf("second write failed: %v", err)
	}
	data, _ := os.ReadFile(path)
	var s Snapshot
	_ = json.Unmarshal(data, &s)
	if s.SendsOK != 3 {
		t.Fatalf("after re-persist SendsOK = %d, want 3 (the latest cumulative)", s.SendsOK)
	}
}
