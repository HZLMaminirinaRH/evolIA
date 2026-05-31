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
		sendBlock(self, localV, localWork, params, peers, key, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf)

		// Relay any externally-dropped vault block once (received UDP blocks are
		// marked seen, so they are never re-propagated — no amplification).
		mu.Lock()
		blocks, err := mesh.NewBlocks(vault, seen)
		mu.Unlock()
		if err != nil {
			logf("scan error: " + err.Error())
		}
		for _, b := range blocks {
			sendBlock(b.Device, b.VValue, b.Work, params, peers, key, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf)
		}

		// Carry any queued chat envelopes to peers (opaque; never decrypted).
		relayChat(paths.ChatOutbox(), peers, egressLimit, health, stats, &egressThrottled, &coldSkipped, logf)

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
			stats.Record(meshstats.DefenseThrottled)
			continue
		}
		b, perr := mesh.ParseBlock(buf[:n])
		if perr != nil {
			attacks.Add(1)
			switch perr {
			case mesh.ErrInjection:
				stats.RecordAttack(meshstats.BlockFlow, meshstats.Injection)
				logf(fmt.Sprintf("injection from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.SQLInjection)))
			case mesh.ErrBadSignature:
				stats.RecordAttack(meshstats.BlockFlow, meshstats.BadSignature)
				logf(fmt.Sprintf("bad signature from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.BadSignature)))
			default:
				attacks.Add(1)
				stats.RecordAttack(meshstats.BlockFlow, meshstats.Malformed)
				logf(fmt.Sprintf("malformed from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.Malformed)))
			}
			continue
		}
		if len(key) > 0 {
			if !bridge.VerifyBlock(b, key) {
				attacks.Add(1)
				stats.RecordAttack(meshstats.BlockFlow, meshstats.BadSignature)
				logf(fmt.Sprintf("bad signature from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.BadSignature)))
				continue
			}
		}
		if perr := pow.ValidateWork(b); perr != nil {
			attacks.Add(1)
			stats.RecordAttack(meshstats.BlockFlow, meshstats.ForgedWork)
			logf(fmt.Sprintf("forged work from %s rejected (defense=%.2f)", src.IP.String(), def.Record(defense.ForgedWork)))
			continue
		}
		mu.Lock()
		if stored, err := mesh.StoreIncoming(vault, b, seen); err != nil {
			logf("block store error: " + err.Error())
		} else if stored {
			throttled.Add(1)
			received.Add(1)
			stats.Record(meshstats.BlockReceived)
			logf("block received device=" + b.Device + " v=" + fmt.Sprintf("%.2f", b.VValue))
		}
		mu.Unlock()
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

func sendBlock(device string, v float64, work json.RawMessage, params mesh.CognitiveParams, peers []string, key []byte, limiter *egress.Limiter, health *peerhealth.Tracker, stats *meshstats.Recorder, egressThrottled, coldSkipped *atomic.Uint64, logf func(string)) {
	b := mesh.Block{Device: device, VValue: v, Work: work, Params: params, TS: time.Now().Unix()}
	if len(key) > 0 {
		b.Sig = bridge.SignBlock(b, key)
	}
	data, _ := json.Marshal(b)
	for _, peer := range peers {
		if !health.MaySend(peer) {
			coldSkipped.Add(1)
			stats.Record(meshstats.ColdSkipped)
			continue
		}
		if !limiter.Allow(peer) {
			egressThrottled.Add(1)
			stats.Record(meshstats.EgressThrottled)
			continue
		}
		addr, err := net.ResolveUDPAddr("udp", peer+":5555")
		if err != nil {
			health.RecordFailure(peer)
			stats.Record(meshstats.SendFail)
			continue
		}
		conn, err := net.DialUDP("udp", nil, addr)
		if err != nil {
			health.RecordFailure(peer)
			stats.Record(meshstats.SendFail)
			continue
		}
		_, err = conn.Write(data)
		conn.Close()
		if err != nil {
			health.RecordFailure(peer)
			stats.Record(meshstats.SendFail)
		} else {
			health.RecordSuccess(peer)
			stats.Record(meshstats.SendOK)
		}
	}
}

func relayChat(outboxPath string, peers []string, limiter *egress.Limiter, health *peerhealth.Tracker, stats *meshstats.Recorder, egressThrottled, coldSkipped *atomic.Uint64, logf func(string)) {
	envelopes, err := chat.DrainOutbox(outboxPath)
	if err != nil || len(envelopes) == 0 {
		return
	}
	for _, e := range envelopes {
		data, _ := json.Marshal(e)
		for _, peer := range peers {
			if !health.MaySend(peer) {
				coldSkipped.Add(1)
				stats.Record(meshstats.ColdSkipped)
				continue
			}
			if !limiter.Allow(peer) {
				egressThrottled.Add(1)
				stats.Record(meshstats.EgressThrottled)
				continue
			}
			addr, err := net.ResolveUDPAddr("udp", peer+":5556")
			if err != nil {
				health.RecordFailure(peer)
				stats.Record(meshstats.SendFail)
				continue
			}
			conn, err := net.DialUDP("udp", nil, addr)
			if err != nil {
				health.RecordFailure(peer)
				stats.Record(meshstats.SendFail)
				continue
			}
			_, err = conn.Write(data)
			conn.Close()
			if err != nil {
				health.RecordFailure(peer)
				stats.Record(meshstats.SendFail)
			} else {
				health.RecordSuccess(peer)
				stats.Record(meshstats.SendOK)
			}
		}
	}
	chat.RequeueUndelivered(outboxPath, envelopes)
}

func readLocalBlock() (float64, json.RawMessage) {
	id := paths.IdentityState()
	if id == nil {
		return 0, json.RawMessage("null")
	}
	work, _ := json.Marshal(id.Work)
	return id.V, work
}

func parsePeers(s string) []string {
	if s == "" {
		return nil
	}
	return strings.Split(s, ",")
}

func dedupe(peers []string) []string {
	seen := map[string]bool{}
	var result []string
	for _, p := range peers {
		if p != "" && !seen[p] {
			seen[p] = true
			result = append(result, p)
		}
	}
	return result
}

func loadParams(path string) mesh.CognitiveParams {
	if path == "" {
		return mesh.CognitiveParams{}
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return mesh.CognitiveParams{}
	}
	var p mesh.CognitiveParams
	_ = json.Unmarshal(data, &p)
	return p
}
