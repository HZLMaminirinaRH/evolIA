package netdisc

import (
	"encoding/json"
	"os"
	"testing"

	"evolia/paths"
)

func TestParseAnnounce(t *testing.T) {
	id, ok := ParseAnnounce([]byte(`{"device_id":"node-1"}`))
	if !ok || id != "node-1" {
		t.Fatalf("want node-1, got %q ok=%v", id, ok)
	}
	if _, ok := ParseAnnounce([]byte(`{"device_id":""}`)); ok {
		t.Fatal("empty device id should be rejected")
	}
	if _, ok := ParseAnnounce([]byte(`garbage`)); ok {
		t.Fatal("garbage should be rejected")
	}
}

func TestRegistryAddDedupAndList(t *testing.T) {
	r := NewRegistry()
	if !r.Add("d2", "10.0.0.2") {
		t.Fatal("first add should be new")
	}
	if !r.Add("d1", "10.0.0.1") {
		t.Fatal("first add should be new")
	}
	if r.Add("d1", "10.0.0.9") {
		t.Fatal("re-add of existing device should not be new")
	}
	list := r.List()
	if len(list) != 2 || list[0].DeviceID != "d1" || list[1].DeviceID != "d2" {
		t.Fatalf("unexpected list: %+v", list)
	}
	if list[0].Addr != "10.0.0.9" {
		t.Fatalf("addr should be refreshed, got %q", list[0].Addr)
	}
}

func TestRegistrySaveWritesPeersFile(t *testing.T) {
	t.Setenv("EVOLIA_HOME", t.TempDir())
	r := NewRegistry()
	r.Add("d1", "10.0.0.1")
	if err := r.Save(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(paths.PeersFile())
	if err != nil {
		t.Fatal(err)
	}
	var peers []Peer
	if err := json.Unmarshal(data, &peers); err != nil {
		t.Fatal(err)
	}
	if len(peers) != 1 || peers[0].Addr != "10.0.0.1" {
		t.Fatalf("unexpected peers file: %+v", peers)
	}
}
