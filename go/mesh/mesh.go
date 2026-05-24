// Package mesh implements detection and propagation of evolIA mesh blocks.
//
// Blocks are JSON files dropped into the mesh vault under EVOLIA_HOME. This
// package mirrors the shared layout so the Go mesh-sync service reads the same
// vault the rest of the system writes to, and the same peer list evolia-net
// discovers.
package mesh

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"

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
