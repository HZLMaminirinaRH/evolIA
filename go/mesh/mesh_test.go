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

func TestVaultDirHonorsEnv(t *testing.T) {
	t.Setenv("EVOLIA_HOME", "/tmp/evolia-xyz")
	if got := VaultDir(); got != filepath.Join("/tmp/evolia-xyz", "evolia_mesh_vault") {
		t.Fatalf("unexpected vault dir: %q", got)
	}
}
