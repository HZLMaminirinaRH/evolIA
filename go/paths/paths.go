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

// IdentityState holds this node's headline value (total_v) — the block it emits.
func IdentityState() string { return filepath.Join(Home(), "evolia_identity_state.json") }

// PeersFile is where evolia-net records discovered peers and mesh-sync reads them.
func PeersFile() string { return filepath.Join(Home(), "evolia_peers.json") }

// CognitiveParams is the fused cognitive-parameter file the bridge maintains.
func CognitiveParams() string { return filepath.Join(Home(), "evolia_cognitive_params.json") }

// MeshSyncLog, NetworkLog and BridgeLog are the per-service JSON-line logs.
func MeshSyncLog() string { return filepath.Join(Home(), "evolia_mesh_sync.log") }
func NetworkLog() string  { return filepath.Join(Home(), "evolia_network.log") }
func BridgeLog() string   { return filepath.Join(Home(), "evolia_bridge.log") }

// DeviceID resolves this node's id: EVOLIA_DEVICE_ID, else hostname, else a default.
func DeviceID() string {
	if v := os.Getenv("EVOLIA_DEVICE_ID"); v != "" {
		return v
	}
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "evolia-node"
}
