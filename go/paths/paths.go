// Package paths resolves the shared evolIA on-disk layout for the Go services.
//
// It mirrors the Rust evolia-core crate and the Python evolia_paths module so
// every language reads and writes the same EVOLIA_HOME files.
package paths

import (
	"os"
	"path/filepath"
	"strconv"
)

// WriteFileAtomic writes data durably: a temp file in the same directory,
// flushed and synced, then atomically renamed into place. A crash or kill
// (signal 9) mid-write can never leave a half-written state file — readers see
// either the old or the new content, never a torn one.
func WriteFileAtomic(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	tmp := f.Name()
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, perm); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// workProofFile is the latest cycle's cognitive proof-of-work, written by the
// Python value producer and attached by mesh-sync to the value it emits.
const workProofFile = "evolia_work_proof.json"

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

// WorkProof is the latest cycle's cognitive proof-of-work the value producer
// writes and mesh-sync attaches to the value it emits.
func WorkProof() string { return filepath.Join(Home(), workProofFile) }

// MeshSyncLog, NetworkLog and BridgeLog are the per-service JSON-line logs.
func MeshSyncLog() string { return filepath.Join(Home(), "evolia_mesh_sync.log") }
func NetworkLog() string  { return filepath.Join(Home(), "evolia_network.log") }
func BridgeLog() string   { return filepath.Join(Home(), "evolia_bridge.log") }

// GenesisUnix is the fleet-wide genesis timestamp (Unix seconds) from
// EVOLIA_GENESIS_UNIX, the anchor for the proof-of-work value ceiling. It must
// be the SAME value on every node (like EVOLIA_MESH_KEY), so all verifiers size
// the ceiling identically; a per-node "now" would make peers reject each other.
// Returns 0 when unset or invalid, which disables the ceiling (the per-increment
// PoW checks still apply).
func GenesisUnix() int64 {
	if s := os.Getenv("EVOLIA_GENESIS_UNIX"); s != "" {
		if v, err := strconv.ParseInt(s, 10, 64); err == nil && v > 0 {
			return v
		}
	}
	return 0
}

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
