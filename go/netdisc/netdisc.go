// Package netdisc implements LAN peer discovery for evolIA.
//
// Nodes broadcast a small announce datagram; listeners register the sender and
// persist the peer list to evolia_peers.json, which mesh-sync reads to know
// where to propagate blocks. The registry and announce parsing are pure and
// testable; the UDP wiring lives in cmd/evolia-net.
package netdisc

import (
	"encoding/json"
	"os"
	"sort"
	"sync"
	"time"

	"evolia/paths"
)

// Announce is the datagram a node broadcasts to advertise itself.
type Announce struct {
	DeviceID string `json:"device_id"`
}

// Peer is a discovered node.
type Peer struct {
	DeviceID string `json:"device_id"`
	Addr     string `json:"addr"`
	LastSeen string `json:"last_seen"`
}

// ParseAnnounce extracts the device id from an announce datagram.
func ParseAnnounce(data []byte) (string, bool) {
	var a Announce
	if err := json.Unmarshal(data, &a); err != nil || a.DeviceID == "" {
		return "", false
	}
	return a.DeviceID, true
}

// Registry is a concurrency-safe set of peers keyed by device id.
type Registry struct {
	mu    sync.Mutex
	peers map[string]Peer
}

func NewRegistry() *Registry {
	return &Registry{peers: make(map[string]Peer)}
}

// Add records or refreshes a peer. Returns true if the peer was new.
func (r *Registry) Add(deviceID, addr string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	_, existed := r.peers[deviceID]
	r.peers[deviceID] = Peer{
		DeviceID: deviceID,
		Addr:     addr,
		LastSeen: time.Now().UTC().Format(time.RFC3339),
	}
	return !existed
}

// List returns the peers sorted by device id.
func (r *Registry) List() []Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Peer, 0, len(r.peers))
	for _, p := range r.peers {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].DeviceID < out[j].DeviceID })
	return out
}

// Save persists the peer list to evolia_peers.json.
func (r *Registry) Save() error {
	data, err := json.MarshalIndent(r.List(), "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(paths.PeersFile(), data, 0o600)
}
