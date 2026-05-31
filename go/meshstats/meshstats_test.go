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

func TestIncSendOK_LandsOnSendsOK(t *testing.T) {
	r := NewRecorder()
	r.IncSendOK()
	r.IncSendOK()
	r.IncSendOK()
	if got := r.Snapshot(0, 0, 0).SendsOK; got != 3 {
		t.Fatalf("SendsOK after 3 increments = %d, want 3", got)
	}
}

func TestIncSendFail_LandsOnSendsFail(t *testing.T) {
	r := NewRecorder()
	r.IncSendFail()
	if got := r.Snapshot(0, 0, 0).SendsFail; got != 1 {
		t.Fatalf("SendsFail = %d, want 1", got)
	}
	// SendsOK must be untouched.
	if got := r.Snapshot(0, 0, 0).SendsOK; got != 0 {
		t.Fatalf("SendsOK leaked from SendFail path: %d, want 0", got)
	}
}

func TestIncThrottleEvents_RouteIndependently(t *testing.T) {
	r := NewRecorder()
	r.IncEgressThrottled()
	r.IncEgressThrottled()
	r.IncDefenseThrottled()
	r.IncColdSkipped()
	r.IncColdSkipped()
	r.IncColdSkipped()
	s := r.Snapshot(0, 0, 0)
	if s.ThrottleEvents.Egress != 2 {
		t.Fatalf("Egress = %d, want 2", s.ThrottleEvents.Egress)
	}
	if s.ThrottleEvents.IngressDefense != 1 {
		t.Fatalf("IngressDefense = %d, want 1", s.ThrottleEvents.IngressDefense)
	}
	if s.ThrottleEvents.ColdSkipped != 3 {
		t.Fatalf("ColdSkipped = %d, want 3", s.ThrottleEvents.ColdSkipped)
	}
}

func TestIncBlockAttack_AllKindsRouted(t *testing.T) {
	r := NewRecorder()
	r.IncBlockAttack("injection")
	r.IncBlockAttack("bad_signature")
	r.IncBlockAttack("bad_signature")
	r.IncBlockAttack("forged_work")
	r.IncBlockAttack("malformed")
	r.IncBlockAttack("malformed")
	r.IncBlockAttack("malformed")
	b := r.Snapshot(0, 0, 0).AttacksByFlow.Blocks
	if b.Injection != 1 || b.BadSignature != 2 || b.ForgedWork != 1 || b.Malformed != 3 {
		t.Fatalf("block attack routing wrong: got %+v", b)
	}
}

func TestIncBlockAttack_UnknownKindIgnored(t *testing.T) {
	r := NewRecorder()
	r.IncBlockAttack("unknown_kind_from_future")
	b := r.Snapshot(0, 0, 0).AttacksByFlow.Blocks
	if b.Injection|b.BadSignature|b.ForgedWork|b.Malformed != 0 {
		t.Fatalf("unknown attack kind must not land on any counter: %+v", b)
	}
}

func TestIncChatAttack_TwoKindsRouted(t *testing.T) {
	r := NewRecorder()
	r.IncChatAttack("injection")
	r.IncChatAttack("malformed")
	r.IncChatAttack("malformed")
	c := r.Snapshot(0, 0, 0).AttacksByFlow.Chat
	if c.Injection != 1 || c.Malformed != 2 {
		t.Fatalf("chat attack routing wrong: got %+v", c)
	}
}

func TestReceives_RouteByFlow(t *testing.T) {
	r := NewRecorder()
	r.IncBlockReceived()
	r.IncBlockReceived()
	r.IncChatReceived()
	rcv := r.Snapshot(0, 0, 0).Receives
	if rcv.Blocks != 2 || rcv.Chat != 1 {
		t.Fatalf("receive routing wrong: got %+v", rcv)
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
	r.IncSendOK()
	r.IncSendFail()
	r.IncBlockAttack("forged_work")
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
	r.IncSendOK()
	if err := r.PersistTo(path, 0, 0, 0); err != nil {
		t.Fatalf("first write failed: %v", err)
	}
	r.IncSendOK()
	r.IncSendOK()
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
