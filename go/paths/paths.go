// Package paths resolves the shared evolIA on-disk layout for the Go services.
//
// It mirrors the Rust evolia-core crate and the Python evolia_paths module so
// every language reads and writes the same EVOLIA_HOME files.
package paths

import (
	"os"
	"path/filepath"
)

// Home resolves EVOLIA_HOME, defaulting to $HOME/evolia.
func Home() string {
	if h := os.Getenv("EVOLIA_HOME"); h != "" {
		return h
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "evolia"
	}
	return filepath.Join(home, "evolia")
}

// MeshVault is the directory of mesh blocks.
func MeshVault() string { return filepath.Join(Home(), "evolia_mesh_vault") }

// PeersFile is where evolia-net records discovered peers and mesh-sync reads them.
func PeersFile() string { return filepath.Join(Home(), "evolia_peers.json") }

// MeshSyncLog and NetworkLog are the per-service JSON-line logs.
func MeshSyncLog() string { return filepath.Join(Home(), "evolia_mesh_sync.log") }
func NetworkLog() string  { return filepath.Join(Home(), "evolia_network.log") }
