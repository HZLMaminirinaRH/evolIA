package paths

import (
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
