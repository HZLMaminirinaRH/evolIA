// Package meshstats persists UDP mesh transport telemetry to a JSON file the
// Android diagnostic UI can read, parallel to the Bluetooth transport's
// evolia_chat_bt_stats.json. The shape is deliberately the requested one —
// sends_ok, sends_fail, peers_cold, attacks_by_flow, throttle_events — so the
// UI does not need any schema translation.
//
// Counters are atomic and safe to increment from any goroutine. PersistTo
// takes a consistent snapshot under no lock (each counter is read once) and
// atomically replaces the file, so a half-written stats file can never be
// observed even if the writer is signal-9'd mid-call.
package meshstats

import (
	"encoding/json"
	"sync/atomic"
	"time"

	"evolia/paths"
)

// Recorder owns the in-memory counters. NewRecorder allocates a zeroed
// recorder; tests can compare snapshots to verify each increment lands on the
// right counter.
type Recorder struct {
	// Sends.
	sendsOK   atomic.Uint64
	sendsFail atomic.Uint64

	// Throttle events.
	egressThrottled  atomic.Uint64
	defenseThrottled atomic.Uint64
	coldSkipped      atomic.Uint64

	// Receives (valid intake).
	blocksReceived atomic.Uint64
	chatReceived   atomic.Uint64

	// Attacks by flow + kind (severity feeds the defense buffer; counts are
	// for diagnostics so the UI can say "3 injections, 1 forged work").
	blockInjection    atomic.Uint64
	blockBadSignature atomic.Uint64
	blockForgedWork   atomic.Uint64
	blockMalformed    atomic.Uint64
	chatInjection     atomic.Uint64
	chatMalformed     atomic.Uint64
}

// NewRecorder builds a recorder with all counters at zero.
func NewRecorder() *Recorder { return &Recorder{} }

// IncSendOK records one successful outbound Write (block or chat).
func (r *Recorder) IncSendOK() { r.sendsOK.Add(1) }

// IncSendFail records one outbound Dial or Write error — the same event that
// arms the peer-health backoff.
func (r *Recorder) IncSendFail() { r.sendsFail.Add(1) }

// IncEgressThrottled records one outbound drop by the egress rate limiter
// (self-DoS guard). Not an attack — preventive shaping.
func (r *Recorder) IncEgressThrottled() { r.egressThrottled.Add(1) }

// IncDefenseThrottled records one inbound drop by the defense Gate (under
// hostile pressure). Distinct from the egress throttle: this is reactive.
func (r *Recorder) IncDefenseThrottled() { r.defenseThrottled.Add(1) }

// IncColdSkipped records one peer-health backoff skip (no dial attempted).
func (r *Recorder) IncColdSkipped() { r.coldSkipped.Add(1) }

// IncBlockReceived records one valid block stored in the vault.
func (r *Recorder) IncBlockReceived() { r.blocksReceived.Add(1) }

// IncChatReceived records one valid chat envelope appended to the inbox.
func (r *Recorder) IncChatReceived() { r.chatReceived.Add(1) }

// IncBlockAttack records a hostile block intake of the given kind. Unknown
// kinds are ignored so a new attack kind added upstream does not panic the
// recorder; they still show in the defense log.
func (r *Recorder) IncBlockAttack(kind string) {
	switch kind {
	case "injection":
		r.blockInjection.Add(1)
	case "bad_signature":
		r.blockBadSignature.Add(1)
	case "forged_work":
		r.blockForgedWork.Add(1)
	case "malformed":
		r.blockMalformed.Add(1)
	}
}

// IncChatAttack records a hostile chat intake. Chat has fewer kinds (no
// signature, no PoW) — only injection and malformed.
func (r *Recorder) IncChatAttack(kind string) {
	switch kind {
	case "injection":
		r.chatInjection.Add(1)
	case "malformed":
		r.chatMalformed.Add(1)
	}
}

// Snapshot is the JSON shape persisted to disk. The field names match the
// counters the user asked for (sends_ok, sends_fail, peers_cold, attacks_by_flow,
// throttle_events) so the Android UI consumes the file without any mapping.
type Snapshot struct {
	UpdatedAt      string         `json:"updated_at"`
	SendsOK        uint64         `json:"sends_ok"`
	SendsFail      uint64         `json:"sends_fail"`
	PeersCold      int            `json:"peers_cold"`
	PeersKnown     int            `json:"peers_known"`
	ThrottleEvents ThrottleCounts `json:"throttle_events"`
	AttacksByFlow  AttacksByFlow  `json:"attacks_by_flow"`
	Receives       ReceiveCounts  `json:"receives"`
	DefenseLevel   float64        `json:"defense_level"`
}

// ThrottleCounts groups the three throttle reasons so the UI can show why a
// send did not go out (egress shaping, defense gate, or cold-peer skip).
type ThrottleCounts struct {
	Egress         uint64 `json:"egress"`
	IngressDefense uint64 `json:"ingress_defense"`
	ColdSkipped    uint64 `json:"cold_skipped"`
}

// AttacksByFlow is the per-flow attack breakdown. Each leaf is a count;
// severity is intentionally not exposed (it's encoded in the defense buffer
// level, also persisted in the snapshot).
type AttacksByFlow struct {
	Blocks BlockAttackKinds `json:"blocks"`
	Chat   ChatAttackKinds  `json:"chat"`
}

// BlockAttackKinds enumerates the attack kinds the block intake classifies.
// Anything else (currently nothing) would be silently dropped by IncBlockAttack.
type BlockAttackKinds struct {
	Injection    uint64 `json:"injection"`
	BadSignature uint64 `json:"bad_signature"`
	ForgedWork   uint64 `json:"forged_work"`
	Malformed    uint64 `json:"malformed"`
}

// ChatAttackKinds enumerates the attack kinds the chat intake classifies.
// Chat envelopes have no signature and no PoW, hence the smaller surface.
type ChatAttackKinds struct {
	Injection uint64 `json:"injection"`
	Malformed uint64 `json:"malformed"`
}

// ReceiveCounts groups successful intake counts by flow.
type ReceiveCounts struct {
	Blocks uint64 `json:"blocks"`
	Chat   uint64 `json:"chat"`
}

// Snapshot reads every counter once and folds in the externally-supplied
// dynamic state (cold/known peer counts, defense level) so the file reflects
// both monotonic activity and the live system snapshot at this moment.
func (r *Recorder) Snapshot(peersCold, peersKnown int, defenseLevel float64) Snapshot {
	return Snapshot{
		UpdatedAt:  time.Now().UTC().Format(time.RFC3339),
		SendsOK:    r.sendsOK.Load(),
		SendsFail:  r.sendsFail.Load(),
		PeersCold:  peersCold,
		PeersKnown: peersKnown,
		ThrottleEvents: ThrottleCounts{
			Egress:         r.egressThrottled.Load(),
			IngressDefense: r.defenseThrottled.Load(),
			ColdSkipped:    r.coldSkipped.Load(),
		},
		AttacksByFlow: AttacksByFlow{
			Blocks: BlockAttackKinds{
				Injection:    r.blockInjection.Load(),
				BadSignature: r.blockBadSignature.Load(),
				ForgedWork:   r.blockForgedWork.Load(),
				Malformed:    r.blockMalformed.Load(),
			},
			Chat: ChatAttackKinds{
				Injection: r.chatInjection.Load(),
				Malformed: r.chatMalformed.Load(),
			},
		},
		Receives: ReceiveCounts{
			Blocks: r.blocksReceived.Load(),
			Chat:   r.chatReceived.Load(),
		},
		DefenseLevel: defenseLevel,
	}
}

// PersistTo writes a snapshot to path atomically (temp file + fsync + rename,
// via paths.WriteFileAtomic). A half-written stats file is impossible — the
// reader sees either the previous version or the new one.
func (r *Recorder) PersistTo(path string, peersCold, peersKnown int, defenseLevel float64) error {
	data, err := json.MarshalIndent(r.Snapshot(peersCold, peersKnown, defenseLevel), "", "  ")
	if err != nil {
		return err
	}
	return paths.WriteFileAtomic(path, data, 0o600)
}
