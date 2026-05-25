// Package mesh implements detection and propagation of evolIA mesh blocks.
//
// Blocks are JSON files dropped into the mesh vault under EVOLIA_HOME. This
// package mirrors the shared layout so the Go mesh-sync service reads the same
// vault the rest of the system writes to, and the same peer list evolia-net
// discovers.
package mesh

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"evolia/paths"
)

// Block is the minimal shape read from a mesh vault file.
type Block struct {
	Name   string  `json:"-"`
	Device string  `json:"device_id"`
	VValue float64 `json:"v_value"`
}

// NewBlocks returns the vault's *.json files not already in seen, marking each
// returned file as seen. Files that fail to parse are skipped (but still marked
// seen so a broken file is not retried forever).
func NewBlocks(vault string, seen map[string]bool) ([]Block, error) {
	matches, err := filepath.Glob(filepath.Join(vault, "*.json"))
	if err != nil {
		return nil, err
	}
	sort.Strings(matches)

	var out []Block
	for _, path := range matches {
		name := filepath.Base(path)
		if seen[name] {
			continue
		}
		seen[name] = true

		b, err := readBlock(path)
		if err != nil {
			continue
		}
		b.Name = name
		out = append(out, b)
	}
	return out, nil
}

// TotalV sums v_value across all blocks currently in the vault.
func TotalV(vault string) float64 {
	matches, err := filepath.Glob(filepath.Join(vault, "*.json"))
	if err != nil {
		return 0
	}
	var total float64
	for _, path := range matches {
		if b, err := readBlock(path); err == nil {
			total += b.VValue
		}
	}
	return total
}

// StoreIncoming parses a block datagram propagated by a peer's mesh-sync and
// writes it into the local vault, so TotalV and the dashboard pick it up. The
// file is keyed by device id (recv_<device>.json), so re-sends from the same
// peer overwrite rather than accumulate. Returns the file name written, which
// the caller should mark "seen" to avoid re-propagating a received block.
func StoreIncoming(vault string, data []byte) (string, error) {
	var b Block
	if err := json.Unmarshal(data, &b); err != nil {
		return "", err
	}
	if b.Device == "" {
		return "", errors.New("block missing device_id")
	}
	if err := os.MkdirAll(vault, 0o700); err != nil {
		return "", err
	}
	name := fmt.Sprintf("recv_%s.json", sanitizeDevice(b.Device))
	payload, _ := json.Marshal(map[string]any{
		"device_id": b.Device,
		"v_value":   b.VValue,
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
	if err := os.WriteFile(filepath.Join(vault, name), payload, 0o600); err != nil {
		return "", err
	}
	return name, nil
}

func sanitizeDevice(s string) string {
	r := strings.NewReplacer("/", "_", "\\", "_", ".", "_", " ", "_", ":", "_")
	if out := r.Replace(s); out != "" {
		return out
	}
	return "peer"
}

func readBlock(path string) (Block, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Block{}, err
	}
	var b Block
	if err := json.Unmarshal(data, &b); err != nil {
		return Block{}, err
	}
	return b, nil
}

// LoadPeers returns peer host addresses from the peers file written by
// evolia-net. Returns nil if the file is missing or unreadable.
func LoadPeers() []string {
	data, err := os.ReadFile(paths.PeersFile())
	if err != nil {
		return nil
	}
	var peers []struct {
		Addr string `json:"addr"`
	}
	if json.Unmarshal(data, &peers) != nil {
		return nil
	}
	var out []string
	for _, p := range peers {
		if p.Addr != "" {
			out = append(out, p.Addr)
		}
	}
	return out
}
