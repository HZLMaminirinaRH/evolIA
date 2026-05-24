package mesh

import (
	"os"
	"path/filepath"
	"testing"
)

func write(t *testing.T, dir, name, body string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestNewBlocksDetectsEachFileOnce(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, "a.json", `{"device_id":"d1","v_value":2.5}`)
	write(t, dir, "b.json", `{"device_id":"d2","v_value":1.0}`)

	seen := map[string]bool{}
	blocks, err := NewBlocks(dir, seen)
	if err != nil {
		t.Fatal(err)
	}
	if len(blocks) != 2 {
		t.Fatalf("want 2 new blocks, got %d", len(blocks))
	}

	again, err := NewBlocks(dir, seen)
	if err != nil {
		t.Fatal(err)
	}
	if len(again) != 0 {
		t.Fatalf("want 0 new blocks on rescan, got %d", len(again))
	}
}

func TestNewBlocksSkipsInvalidJSON(t *testing.T) {
	dir := t.TempDir()
	write(t, dir, "bad.json", `not json`)
	write(t, dir, "ok.json", `{"v_value":3}`)

	seen := map[string]bool{}
	blocks, err := NewBlocks(dir, seen)
	if err != nil {
		t.Fatal(err)
	}
	if len(blocks) != 1 {
		t.Fatalf("want 1 valid block, got %d", len(blocks))
	}
	if blocks[0].VValue != 3 {
		t.Fatalf("want v_value 3, got %v", blocks[0].VValue)
	}
	if blocks[0].Name != "ok.json" {
		t.Fatalf("want name ok.json, got %q", blocks[0].Name)
	}
}

func TestLoadPeersReadsPeersFile(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("EVOLIA_HOME", dir)
	write(t, dir, "evolia_peers.json",
		`[{"device_id":"d1","addr":"192.168.1.5"},{"device_id":"d2","addr":"192.168.1.6"}]`)

	peers := LoadPeers()
	if len(peers) != 2 || peers[0] != "192.168.1.5" || peers[1] != "192.168.1.6" {
		t.Fatalf("unexpected peers: %v", peers)
	}
}

func TestLoadPeersMissingFile(t *testing.T) {
	t.Setenv("EVOLIA_HOME", t.TempDir())
	if peers := LoadPeers(); peers != nil {
		t.Fatalf("want nil for missing peers file, got %v", peers)
	}
}
