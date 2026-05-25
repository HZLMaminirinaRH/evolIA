package paths

import (
	"os"
	"path/filepath"
	"testing"
)

func TestHonorsEnv(t *testing.T) {
	t.Setenv("EVOLIA_HOME", "/tmp/evolia-xyz")
	if got := Home(); got != "/tmp/evolia-xyz" {
		t.Fatalf("Home = %q", got)
	}
	if got := MeshVault(); got != filepath.Join("/tmp/evolia-xyz", "evolia_mesh_vault") {
		t.Fatalf("MeshVault = %q", got)
	}
	if got := PeersFile(); got != filepath.Join("/tmp/evolia-xyz", "evolia_peers.json") {
		t.Fatalf("PeersFile = %q", got)
	}
}

func TestWriteFileAtomicPersistsAndLeavesNoTemp(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "evolia_peers.json")

	if err := WriteFileAtomic(path, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	// Overwriting yields exactly the new content (no torn write, no append).
	if err := WriteFileAtomic(path, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(path); string(got) != "second" {
		t.Fatalf("content = %q, want %q", got, "second")
	}

	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("want exactly 1 file (no leftover temp), got %d: %v", len(entries), entries)
	}
	if info, _ := os.Stat(path); info.Mode().Perm() != 0o600 {
		t.Fatalf("perm = %v, want 0600", info.Mode().Perm())
	}
}
