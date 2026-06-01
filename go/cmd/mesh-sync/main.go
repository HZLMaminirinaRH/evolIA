// Command mesh-sync emits this node's value to peers and receives theirs, and
// relays the app's opaque end-to-end chat envelopes alongside.
//
// Each cycle it emits the local value (read from evolia_identity_state.json) as
// a signed block to configured peers over UDP (EVOLIA_PEERS, comma-separated
// host[:port], default port 5555) plus whatever evolia-net discovered, carrying
// the cognitive params so the formula co-evolves across the mesh. It also
// relays any externally-dropped vault block once. A UDP listener on :5555
// receives peer blocks, verifies their HMAC signature (shared EVOLIA_MESH_KEY),
// and stores them — feeding the adaptive defense when input is hostile. A
// second UDP listener on :5556 receives opaque chat envelopes, routes those
// addressed to this node into the app's inbox (and never decrypts the body —
// E2E lives in the app), while each cycle drains the app's outbox to peers on
// peer:5556. Events are written to evolia_mesh_sync.log. Standard library only.
//
// Port allocation (no collision with evolia-net):
//
//	:5555 — mesh-sync block intake (value sync)
//	:5556 — mesh-sync chat intake  (opaque E2E envelopes)
//	:5557 — evolia-net discovery   (LAN announces, dedicated port)
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
	"evolia/chat"
	"evolia/defense"
	"evolia/egress"
	"evolia/mesh"
	"evolia/meshstats"
	"evolia/paths"
	"evolia/peerhealth"
	"evolia/pow"
)

const blockAddr = ":5555"
const chatAddr = ":5556"
const chatPort = "5556"

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
	// Flow isolation (Opt 5): separate defenses for blocks and chat so an attack
	// on one flow doesn't throttle the other. Each has its own buffer and gate;
	// they decay independently. The cycle stretches by the max of the two levels.
	defBlocks := defense.New(64)
	defChat := defense.New(64)
	gateBlocks := defense.NewGate(defBlocks, time.Now)
	gateChat := defense.NewGate(defChat, time.Now)
	// Egress limiter shapes our OWN outbound sends per peer host, so a
	// growing peer set or a queued-message blast cannot saturate the radio
	// or batter a receiver. Independent of the defense Gate (which throttles
	// hostile ingress) — egress shaping is preventive, not reactive.
	egressLimit := egress.NewLimiter(time.Now)
	// Peer-health tracker: a dead peer (DNS error, network unreachable, write
	// failure) is skipped during an exponential backoff window (5s → 5min cap)
	// instead of consuming cycle budget every tick. A single success re-warms
	// it instantly. Send-side signal only — receive-side correlation is Phase 3.
	health := peerhealth.NewTracker(time.Now)
	// Telemetry recorder: monotonic counters (sends/receives/throttles/attacks
	// by flow) persisted each cycle to evolia_mesh_stats.json so the Android
	// diagnostic UI can read live transport health, parallel to the existing
	// evolia_chat_bt_stats.json for Bluetooth.
	stats := meshstats.NewRecorder()
	// baseCycle is what the env / default gives us; the actual sleep each tick
	// is defense.AdaptiveCycle(baseCycle, max(defBlocks.Level(), defChat.Level()))
	// so a saturated buffer (on either flow) stretches the loop toward 2× base —
	// battery + outbound surface relief during attack, while the independent
	// listen goroutines keep accepting under their respective gate's pressure.
	baseCycle := cycleInterval()

	logf := newLogger(paths.MeshSyncLog())
	logf(fmt.Sprintf("start device=%s vault=%s base_cycle=%s listen=%s signed=%t",
		self, vault, baseCycle, blockAddr, len(key) > 0))

	seen := map[string]bool{}
	var mu sync.Mutex
	var attacks, received, throttled, egressThrottled, coldSkipped atomic.Uint64

	// Receive blocks propagated by peers and store them in the vault.
	go listenBlocks(vault, seen, &mu, key, defBlocks, gateBlocks, &attacks, &received, &throttled, stats, logf)

	// Relay opaque end-to-end chat alongside the value mesh: a UDP listener on
	// chatAddr delivers inbound envelopes addressed to us into the app's inbox,
	// while each cycle drains the app's outbox to peers. The relay never decrypts
	// a body — E2E lives in the app — and hostile chat input feeds its own
	// adaptive defense (flow isolation), separate from block intake. The seen set
	// is preloaded so a restart does not re-deliver messages already in the inbox.
	chatSeen := chat.LoadSeenIDs(paths.ChatInbox())
	go listenChat(paths.ChatInbox(), chatSeen, paths.ChatFingerprint(), gateChat, defChat, &attacks, stats, logf)

	prevAttacks := attacks.Load()
	prevReceived := received.Load()
	prevThrottled := throttled.Load()
	prevEgressThrottled := egressThrottled.Load()
	prevColdSkipped := coldSkipped.Load()
	for {
		// Peers come from EVOLIA_PEERS plus whatever evolia-net has discovered.
		peers := dedupe(append(parsePeers(os.Getenv("EVOLIA_PEERS")), mesh.LoadPeers()...))
		params := loadParams(paths.CognitiveParams())

		// Emit this node's current value each cycle (signed, with its cognitive
		// proof-of-work and params attached).
		localV, localWork := readLocalBlock()
		reached := sendBlock(self, localV, localWork, params, peers, key, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf)
		if len(peers) > 0 {
			logf(fmt.Sprintf("emit device=%s v=%.4f reached %d/%d peers", self, localV, reached, len(peers)))
		}

		// Relay any externally-dropped vault block once (received UDP blocks are
		// marked seen, so they are never re-propagated — no amplification).
		mu.Lock()
		blocks, err := mesh.NewBlocks(vault, seen)
		mu.Unlock()
		if err != nil {
			logf("scan error: " + err.Error())
		}
		for _, b := range blocks {
			relayed := sendBlock(b.Device, b.VValue, b.Work, params, peers, key, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf)
			logf(fmt.Sprintf("relay vault block device=%s reached %d/%d peers", b.Device, relayed, len(peers)))
		}

		// Carry any queued chat envelopes to peers (opaque; never decrypted).
		if delivered := relayChat(paths.ChatOutbox(), peers, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf); delivered > 0 {
			logf(fmt.Sprintf("chat relay delivered %d datagram(s) across %d peer(s)", delivered, len(peers)))
		}

		// Adaptive cycle: under sustained hostile pressure the loop stretches
		// toward 2× baseCycle so we emit less and spare the radio/battery; at
		// rest it stays at baseCycle. With flow isolation the cycle responds to
		// the max pressure of blocks or chat. Pressure(level) ∈ [0,1] smooths
		// the transition so the cycle cannot flap, and it returns to base
		// automatically as both buffers decay on quiet ticks.
		blockLevel := defBlocks.Level()
		chatLevel := defChat.Level()
		maxLevel := blockLevel
		if chatLevel > maxLevel {
			maxLevel = chatLevel
		}
		cycle := defense.AdaptiveCycle(baseCycle, maxLevel)

		// Persist the telemetry snapshot so the Android UI can read it (and see
		// the live cycle vs the base cycle, plus the two defense levels).
		// Best-effort — a write error is logged but never breaks the cycle (the
		// next snapshot supersedes any half-failed one through the atomic rename).
		if err := stats.PersistTo(paths.MeshStats(), health.ColdCount(), len(peers), blockLevel, chatLevel, baseCycle, cycle); err != nil {
			logf("mesh stats persist error: " + err.Error())
		}
		time.Sleep(cycle)

		// Couple the three live flows into the a_global net intensity, with the
		// absorbed defense (D_evo) as the counterweight: A_evo = attacks this
		// cycle, P_free = peer blocks received (passive propagation), D_evo =
		// the buffer level. We use the combined max for net intensity. Surfaced
		// only when something happened, so quiet cycles stay silent (battery).
		curAttacks := attacks.Load()
		curReceived := received.Load()
		curThrottled := throttled.Load()
		curEgressThrottled := egressThrottled.Load()
		curColdSkipped := coldSkipped.Load()
		aEvo := float64(curAttacks - prevAttacks)
		pFree := 0.1 * float64(curReceived-prevReceived)
		coldNow := health.ColdCount()
		cycleStretched := cycle != baseCycle
		if curAttacks != prevAttacks || curThrottled != prevThrottled || curEgressThrottled != prevEgressThrottled || curColdSkipped != prevColdSkipped || coldNow > 0 || cycleStretched {
			logf(fmt.Sprintf("intensity net=%.2f a_evo=%.1f p_free=%.2f d_evo=%.2f (blocks=%.2f chat=%.2f) throttled=%d egress_throttled=%d cold_peers=%d cold_skipped=%d cycle=%s",
				defense.NetIntensity(aEvo, pFree, maxLevel), aEvo, pFree, maxLevel, blockLevel, chatLevel,
				curThrottled-prevThrottled, curEgressThrottled-prevEgressThrottled,
				coldNow, curColdSkipped-prevColdSkipped, cycle))
		}

		// On a quiet cycle (no hostile datagram since the last one) relax both
		// adaptive defenses one notch, so they breathe back down independently
		// once their respective attacks stop.
		if curAttacks == prevAttacks {
			defBlocks.Decay()
			defChat.Decay()
		}
		prevAttacks = curAttacks
		prevReceived = curReceived
		prevEgressThrottled = curEgressThrottled
		prevColdSkipped = curColdSkipped
		prevThrottled = curThrottled
	}
}

// listenBlocks receives propagated blocks over UDP, verifies + stores them, and
// hardens the defense when input is hostile. Each stored block is marked seen
// under the same lock the relay uses, so a received block is never forwarded
// again (no amplification loops). The gate throttles intake per source under
// defense pressure; a throttled datagram is dropped (not recorded as an attack,
// so the throttle can never feed itself into a runaway).
func listenBlocks(vault string, seen map[string]bool, mu *sync.Mutex, key []byte, def *defense.AdaptiveDefense, gate *defense.Gate, attacks, received, throttled *atomic.Uint64, stats *meshstats.Recorder, logf func(string)) {
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
			stats.Record(meshstats.DefenseThrottled)
			continue
		}
		mu.Lock()
		name, params, storeErr := mesh.StoreIncoming(vault, buf[:n], key, def)
		if storeErr == nil {
			seen[name] = true
		}
		mu.Unlock()

		switch {
		case storeErr == nil:
			received.Add(1)
			stats.Record(meshstats.BlockReceived)
			bridge.FuseIncoming(params)
			logf("received " + name + " from " + src.IP.String())
		case errors.Is(storeErr, mesh.ErrStale):
			// Reordered or replayed value that does not advance our record —
			// not an attack; drop it silently.
		case errors.Is(storeErr, mesh.ErrInjection):
			attacks.Add(1)
			stats.RecordAttack(meshstats.BlockFlow, meshstats.Injection)
			logf(fmt.Sprintf("injection from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.SQLInjection)))
		case errors.Is(storeErr, mesh.ErrForgedWork):
			attacks.Add(1)
			stats.RecordAttack(meshstats.BlockFlow, meshstats.ForgedWork)
			logf(fmt.Sprintf("forged work from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.ForgedWork)))
		case errors.Is(storeErr, mesh.ErrBadSignature):
			attacks.Add(1)
			stats.RecordAttack(meshstats.BlockFlow, meshstats.BadSignature)
			logf(fmt.Sprintf("bad signature from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.BadSignature)))
		default:
			attacks.Add(1)
			stats.RecordAttack(meshstats.BlockFlow, meshstats.Malformed)
			logf(fmt.Sprintf("malformed from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.Malformed)))
		}
	}
}

// listenChat receives opaque chat envelopes over UDP, routes those addressed to
// this node into the app's inbox (deduped by id), and feeds hostile input to the
// adaptive defense. The gate throttles intake per source under pressure; a
// throttled datagram is dropped (not scored, so the throttle can't feed itself).
// The body is never decrypted here — end-to-end decryption is the app's job.
func listenChat(inboxPath string, seen map[string]bool, fpPath string, gate *defense.Gate, def *defense.AdaptiveDefense, attacks *atomic.Uint64, stats *meshstats.Recorder, logf func(string)) {
	addr, err := net.ResolveUDPAddr("udp", chatAddr)
	if err != nil {
		logf("chat listen resolve error: " + err.Error())
		return
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		logf("chat listen error: " + err.Error())
		return
	}
	defer conn.Close()

	buf := make([]byte, chat.MaxDatagramBytes)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		if !gate.Allow(src.IP.String()) {
			stats.Record(meshstats.DefenseThrottled)
			continue
		}
		m, perr := chat.ParseIncoming(buf[:n])
		if perr != nil {
			attacks.Add(1)
			if errors.Is(perr, chat.ErrInjection) {
				stats.RecordAttack(meshstats.ChatFlow, meshstats.Injection)
				logf(fmt.Sprintf("chat injection from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.SQLInjection)))
			} else {
				stats.RecordAttack(meshstats.ChatFlow, meshstats.Malformed)
				logf(fmt.Sprintf("chat malformed from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.Malformed)))
			}
			continue
		}
		if !chat.AddressedTo(m, chat.LoadFingerprint(fpPath)) {
			continue // not for us — Phase 1 is direct delivery, no store-and-forward
		}
		if stored, err := chat.AppendInbox(inboxPath, m, seen); err != nil {
			logf("chat inbox write error: " + err.Error())
		} else if stored {
			stats.Record(meshstats.ChatReceived)
			logf("chat received id=" + m.ID + " from " + src.IP.String())
		}
	}
}

// relayChat drains the app's outbox and carries each opaque envelope to every
// peer over UDP. With no peers known yet it leaves messages queued (so nothing
// is lost before discovery). Delivery is best-effort (UDP); the receiver dedups
// by id, so a future ACK/retry layer can ride on top without protocol changes.
// The egress limiter caps the per-peer-host send rate so a multi-message blast
// across many peers cannot saturate the radio — Phase-2 fan-out reaches the
// addressee via at least one un-throttled peer (the receiver dedups by id).
// Cold peers (in a peer-health backoff window) are skipped without a dial.
// relayChat drains the outbox and fans each sealed envelope out to every peer,
// returning the number of datagrams that actually left the radio (summing
// sendChat's per-peer verdict). The caller logs this so a cycle that drained
// messages but delivered none is visible; a void return would hide that.
func relayChat(outboxPath string, peers []string, egressLimit *egress.Limiter, health *peerhealth.Tracker, stats *meshstats.Recorder, egressThrottled, coldSkipped *atomic.Uint64, logf func(string)) int {
	if len(peers) == 0 {
		return 0
	}
	msgs, err := chat.DrainOutbox(outboxPath)
	if err != nil {
		logf("chat outbox error: " + err.Error())
		return 0
	}
	delivered := 0
	for _, m := range msgs {
		data := m.Wire()
		for _, peer := range peers {
			if sendChat(chatPeerAddr(peer), data, egressLimit, health, stats, egressThrottled, coldSkipped, logf) {
				delivered++
			}
		}
	}
	return delivered
}

// chatPeerAddr maps a peer's mesh address (host or host:blockport) to its chat
// UDP port on the same host.
func chatPeerAddr(peer string) string {
	host := peer
	if h, _, err := net.SplitHostPort(peer); err == nil {
		host = h
	}
	return net.JoinHostPort(host, chatPort)
}

// sendChat carries one opaque chat datagram to a peer and reports whether it
// actually left the radio: true on a successful Write, false when the send was
// skipped (cold peer), throttled (egress), or failed (dial/Write error). The
// caller (relayChat) folds these booleans into a delivered count, so a void
// return would discard the one fact the relay needs to report.
func sendChat(addr string, data []byte, egressLimit *egress.Limiter, health *peerhealth.Tracker, stats *meshstats.Recorder, egressThrottled, coldSkipped *atomic.Uint64, logf func(string)) bool {
	host := peerHost(addr)
	if !health.MaySend(host) {
		coldSkipped.Add(1)
		stats.Record(meshstats.ColdSkipped)
		return false // cold peer in backoff window — skip silently, no dial
	}
	if !egressLimit.Allow(host) {
		egressThrottled.Add(1)
		stats.Record(meshstats.EgressThrottled)
		logf("chat egress throttled -> " + addr)
		return false
	}
	conn, err := net.DialTimeout("udp", addr, time.Second)
	if err != nil {
		consec := health.RecordFailure(host)
		fails := stats.Record(meshstats.SendFail)
		logf(fmt.Sprintf("chat dial %s failed: %s (consec=%d, lifetime_fails=%d)", addr, err.Error(), consec, fails))
		return false
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write(data); err != nil {
		consec := health.RecordFailure(host)
		fails := stats.Record(meshstats.SendFail)
		logf(fmt.Sprintf("chat send %s failed: %s (consec=%d, lifetime_fails=%d)", addr, err.Error(), consec, fails))
		return false
	}
	successes := health.RecordSuccess(host)
	_ = stats.Record(meshstats.SendOK)
	logf(fmt.Sprintf("chat sent -> %s (peer_successes=%d)", addr, successes))
	return true
}

// peerHost strips the port from an addr so the egress limiter buckets by host:
// blocks (:5555) and chat (:5556) targeting the same device share rate budget
// — what matters is the receiver's radio/CPU load, not which port we hit.
func peerHost(addr string) string {
	if h, _, err := net.SplitHostPort(addr); err == nil {
		return h
	}
	return addr
}

// sendBlock emits one signed block to every peer and returns how many peers it
// actually reached (a successful Write). With no peers it returns 0 (the block
// stays local). The caller logs the reached/total ratio, so a cycle where every
// peer is cold or throttled is visible instead of silently swallowed.
func sendBlock(device string, vValue float64, work *pow.WorkProof, params map[string]float64, peers []string, key []byte, egressLimit *egress.Limiter, health *peerhealth.Tracker, stats *meshstats.Recorder, egressThrottled, coldSkipped *atomic.Uint64, logf func(string)) int {
	if len(peers) == 0 {
		logf(fmt.Sprintf("local block device=%s v=%.4f (no peers)", device, vValue))
		return 0
	}
	payload := map[string]any{"device_id": device, "v_value": vValue}
	if len(key) > 0 {
		payload["sig"] = mesh.SignBlock(key, device, vValue)
	}
	if work != nil {
		payload["work"] = work
	}
	if len(params) > 0 {
		payload["cognitive_params"] = params
	}
	data, _ := json.Marshal(payload)

	reached := 0
	for _, peer := range peers {
		addr := peer
		if !strings.Contains(addr, ":") {
			addr += ":5555"
		}
		host := peerHost(addr)
		if !health.MaySend(host) {
			coldSkipped.Add(1)
			stats.Record(meshstats.ColdSkipped)
			continue // cold peer in backoff window — skip silently, no dial
		}
		if !egressLimit.Allow(host) {
			egressThrottled.Add(1)
			stats.Record(meshstats.EgressThrottled)
			logf("egress throttled " + addr + " (burst protection)")
			continue
		}
		conn, err := net.DialTimeout("udp", addr, time.Second)
		if err != nil {
			consec := health.RecordFailure(host)
			fails := stats.Record(meshstats.SendFail)
			logf(fmt.Sprintf("dial %s failed: %s (consec=%d, lifetime_fails=%d)", addr, err.Error(), consec, fails))
			continue
		}
		_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
		if _, err := conn.Write(data); err != nil {
			consec := health.RecordFailure(host)
			fails := stats.Record(meshstats.SendFail)
			logf(fmt.Sprintf("send %s failed: %s (consec=%d, lifetime_fails=%d)", addr, err.Error(), consec, fails))
		} else {
			successes := health.RecordSuccess(host)
			_ = stats.Record(meshstats.SendOK)
			logf(fmt.Sprintf("sent device=%s -> %s (peer_successes=%d)", device, addr, successes))
			reached++
		}
		conn.Close()
	}
	return reached
}

// readLocalBlock returns this node's current value and its cognitive proof-of-
// work, written each cycle by the Python value producer. When the proof file is
// absent (producer not running yet) it falls back to the identity-state value
// with no proof — a keyed fleet will reject such proofless emissions, by design.
func readLocalBlock() (float64, *pow.WorkProof) {
	if data, err := os.ReadFile(paths.WorkProof()); err == nil {
		var p struct {
			VValue float64        `json:"v_value"`
			Work   *pow.WorkProof `json:"work"`
		}
		if json.Unmarshal(data, &p) == nil && p.Work != nil {
			return p.VValue, p.Work
		}
	}
	return readLocalTotalV(), nil
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
