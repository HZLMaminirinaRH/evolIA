// Command mesh-sync emits this node's value to peers and receives theirs.
//
// Each cycle it emits the local value (read from evolia_identity_state.json) as
// a signed block to configured peers over UDP (EVOLIA_PEERS, comma-separated
// host[:port], default port 5555) plus whatever evolia-net discovered, carrying
// the cognitive params so the formula co-evolves across the mesh. It also
// relays any externally-dropped vault block once. A UDP listener on :5555
// receives peer blocks, verifies their HMAC signature (shared EVOLIA_MESH_KEY),
// and stores them — feeding the adaptive defense when input is hostile. Events
// are written to evolia_mesh_sync.log. Standard library only.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"evolia/bridge"
	"evolia/defense"
	"evolia/mesh"
	"evolia/paths"
)

const (
	cycle     = 2 * time.Second
	blockAddr = ":5555"
)

func main() {
	vault := paths.MeshVault()
	if err := os.MkdirAll(vault, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "mesh-sync: cannot create vault:", err)
		os.Exit(1)
	}

	self := paths.DeviceID()
	key := []byte(os.Getenv("EVOLIA_MESH_KEY"))
	def := defense.New(64)

	logf := newLogger(paths.MeshSyncLog())
	logf(fmt.Sprintf("start device=%s vault=%s cycle=%s listen=%s signed=%t",
		self, vault, cycle, blockAddr, len(key) > 0))

	seen := map[string]bool{}
	var mu sync.Mutex
	var attacks atomic.Uint64

	// Receive blocks propagated by peers and store them in the vault.
	go listenBlocks(vault, seen, &mu, key, def, &attacks, logf)

	prevAttacks := attacks.Load()
	for {
		// Peers come from EVOLIA_PEERS plus whatever evolia-net has discovered.
		peers := dedupe(append(parsePeers(os.Getenv("EVOLIA_PEERS")), mesh.LoadPeers()...))
		params := loadParams(paths.CognitiveParams())

		// Emit this node's current value each cycle (signed, params attached).
		sendBlock(self, readLocalTotalV(), params, peers, key, logf)

		// Relay any externally-dropped vault block once (received UDP blocks are
		// marked seen, so they are never re-propagated — no amplification).
		mu.Lock()
		blocks, err := mesh.NewBlocks(vault, seen)
		mu.Unlock()
		if err != nil {
			logf("scan error: " + err.Error())
		}
		for _, b := range blocks {
			sendBlock(b.Device, b.VValue, params, peers, key, logf)
		}
		time.Sleep(cycle)

		// On a quiet cycle (no hostile datagram since the last one) relax the
		// adaptive defense one notch, so it breathes back down once attacks stop.
		if cur := attacks.Load(); cur == prevAttacks {
			def.Decay()
		} else {
			prevAttacks = cur
		}
	}
}

// listenBlocks receives propagated blocks over UDP, verifies + stores them, and
// hardens the defense when input is hostile. Each stored block is marked seen
// under the same lock the relay uses, so a received block is never forwarded
// again (no amplification loops).
func listenBlocks(vault string, seen map[string]bool, mu *sync.Mutex, key []byte, def *defense.AdaptiveDefense, attacks *atomic.Uint64, logf func(string)) {
	addr, err := net.ResolveUDPAddr("udp", blockAddr)
	if err != nil {
		logf("block listen resolve error: " + err.Error())
		return
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		logf("block listen error: " + err.Error())
		return
	}
	defer conn.Close()

	buf := make([]byte, 8192)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		mu.Lock()
		name, params, storeErr := mesh.StoreIncoming(vault, buf[:n], key)
		if storeErr == nil {
			seen[name] = true
		}
		mu.Unlock()

		switch {
		case storeErr == nil:
			bridge.FuseIncoming(params)
			logf("received " + name + " from " + src.IP.String())
		case errors.Is(storeErr, mesh.ErrInjection):
			attacks.Add(1)
			logf(fmt.Sprintf("injection from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.SQLInjection)))
		case errors.Is(storeErr, mesh.ErrBadSignature):
			attacks.Add(1)
			logf(fmt.Sprintf("bad signature from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.BadSignature)))
		default:
			attacks.Add(1)
			logf(fmt.Sprintf("malformed from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.Malformed)))
		}
	}
}

func sendBlock(device string, vValue float64, params map[string]float64, peers []string, key []byte, logf func(string)) {
	if len(peers) == 0 {
		logf(fmt.Sprintf("local block device=%s v=%.4f (no peers)", device, vValue))
		return
	}
	payload := map[string]any{"device_id": device, "v_value": vValue}
	if len(key) > 0 {
		payload["sig"] = mesh.SignBlock(key, device, vValue)
	}
	if len(params) > 0 {
		payload["cognitive_params"] = params
	}
	data, _ := json.Marshal(payload)

	for _, peer := range peers {
		addr := peer
		if !strings.Contains(addr, ":") {
			addr += ":5555"
		}
		conn, err := net.DialTimeout("udp", addr, time.Second)
		if err != nil {
			logf("dial " + addr + " failed: " + err.Error())
			continue
		}
		_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
		if _, err := conn.Write(data); err != nil {
			logf("send " + addr + " failed: " + err.Error())
		} else {
			logf(fmt.Sprintf("sent device=%s -> %s", device, addr))
		}
		conn.Close()
	}
}

func readLocalTotalV() float64 {
	data, err := os.ReadFile(paths.IdentityState())
	if err != nil {
		return 0
	}
	var s struct {
		TotalV float64 `json:"total_v"`
	}
	if json.Unmarshal(data, &s) != nil {
		return 0
	}
	return s.TotalV
}

func loadParams(path string) map[string]float64 {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var m map[string]float64
	if json.Unmarshal(data, &m) != nil {
		return nil
	}
	return m
}

func parsePeers(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func dedupe(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func newLogger(path string) func(string) {
	return func(msg string) {
		fmt.Println("[mesh-sync] " + msg)
		line, _ := json.Marshal(map[string]any{
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"message":   msg,
		})
		if f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
			f.Write(append(line, '\n'))
			f.Close()
		}
	}
}
