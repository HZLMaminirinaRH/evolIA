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
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"evolia/bridge"
	"evolia/defense"
	"evolia/mesh"
	"evolia/paths"
)

const blockAddr = ":5555"

// cycleInterval is the emit/relax cadence. EVOLIA_MESH_CYCLE_SECONDS overrides
// the default of 5s — battery-friendly and aligned with the Python loop's
// EVOLIA_CYCLE_SECONDS, rather than waking the CPU every 2s.
func cycleInterval() time.Duration {
	if s := os.Getenv("EVOLIA_MESH_CYCLE_SECONDS"); s != "" {
		if v, err := strconv.Atoi(s); err == nil && v > 0 {
			return time.Duration(v) * time.Second
		}
	}
	return 5 * time.Second
}

func main() {
	vault := paths.MeshVault()
	if err := os.MkdirAll(vault, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "mesh-sync: cannot create vault:", err)
		os.Exit(1)
	}

	self := paths.DeviceID()
	key := []byte(os.Getenv("EVOLIA_MESH_KEY"))
	def := defense.New(64)
	gate := defense.NewGate(def, time.Now)
	cycle := cycleInterval()

	logf := newLogger(paths.MeshSyncLog())
	logf(fmt.Sprintf("start device=%s vault=%s cycle=%s listen=%s signed=%t",
		self, vault, cycle, blockAddr, len(key) > 0))

	seen := map[string]bool{}
	var mu sync.Mutex
	var attacks, received, throttled atomic.Uint64

	// Receive blocks propagated by peers and store them in the vault.
	go listenBlocks(vault, seen, &mu, key, def, gate, &attacks, &received, &throttled, logf)

	prevAttacks := attacks.Load()
	prevReceived := received.Load()
	prevThrottled := throttled.Load()
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

		// Couple the three live flows into the a_global net intensity, with the
		// absorbed defense (D_evo) as the counterweight: A_evo = attacks this
		// cycle, P_free = peer blocks received (passive propagation), D_evo =
		// the buffer level. Surfaced only when something happened, so quiet
		// cycles stay silent (battery).
		curAttacks := attacks.Load()
		curReceived := received.Load()
		curThrottled := throttled.Load()
		aEvo := float64(curAttacks - prevAttacks)
		pFree := 0.1 * float64(curReceived-prevReceived)
		if curAttacks != prevAttacks || curThrottled != prevThrottled {
			logf(fmt.Sprintf("intensity net=%.2f a_evo=%.1f p_free=%.2f d_evo=%.2f throttled=%d",
				defense.NetIntensity(aEvo, pFree, def.Level()), aEvo, pFree, def.Level(),
				curThrottled-prevThrottled))
		}

		// On a quiet cycle (no hostile datagram since the last one) relax the
		// adaptive defense one notch, so it breathes back down once attacks stop.
		if curAttacks == prevAttacks {
			def.Decay()
		}
		prevAttacks = curAttacks
		prevReceived = curReceived
		prevThrottled = curThrottled
	}
}

// listenBlocks receives propagated blocks over UDP, verifies + stores them, and
// hardens the defense when input is hostile. Each stored block is marked seen
// under the same lock the relay uses, so a received block is never forwarded
// again (no amplification loops). The gate throttles intake per source under
// defense pressure; a throttled datagram is dropped (not recorded as an attack,
// so the throttle can never feed itself into a runaway).
func listenBlocks(vault string, seen map[string]bool, mu *sync.Mutex, key []byte, def *defense.AdaptiveDefense, gate *defense.Gate, attacks, received, throttled *atomic.Uint64, logf func(string)) {
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
		if !gate.Allow(src.IP.String()) {
			throttled.Add(1)
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
			received.Add(1)
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
	// Open the log once and keep it: one write per line instead of an
	// open/write/close syscall trio per message (battery + flash on mobile).
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	var mu sync.Mutex
	return func(msg string) {
		fmt.Println("[mesh-sync] " + msg)
		if f == nil {
			return
		}
		line, _ := json.Marshal(map[string]any{
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"message":   msg,
		})
		mu.Lock()
		f.Write(append(line, '\n'))
		mu.Unlock()
	}
}
