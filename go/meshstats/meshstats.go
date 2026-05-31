// Package meshstats persists UDP mesh transport telemetry to a JSON file the
// Android diagnostic UI can read, parallel to the Bluetooth transport's
// evolia_chat_bt_stats.json. The shape is deliberately the requested one —
// sends_ok, sends_fail, peers_cold, attacks_by_flow, throttle_events — so the
// UI does not need any schema translation.
//
// Counters are atomic and safe to mutate from any goroutine. PersistTo takes
// a consistent snapshot (each counter read once) and atomically replaces the
// file, so a half-written stats file can never be observed even if the writer
// is signal-9'd mid-call.
package meshstats

import (
	"encoding/json"
	"sync/atomic"
	"time"

	"evolia/paths"
)

// Event enumerates the discrete recordable events on the UDP transport. The
// recorder dispatches to the right counter through one method (Record) instead
// of one wrapper per counter — each wrapper would be an unconditional
// increment that questions nothing and returns nothing, so the audit deletes
// them in favour of a single switch that actually interrogates the input.
type Event int

const (
	SendOK           Event = iota // outbound block or chat Write succeeded
	SendFail                      // outbound Dial or Write error
	EgressThrottled               // dropped by the egress limiter (self-DoS guard)
	DefenseThrottled              // dropped by the defense Gate (under hostile pressure)
	ColdSkipped                   // skipped by the peer-health backoff window
	BlockReceived                 // a valid block landed in the vault
	ChatReceived                  // a valid chat envelope landed in the inbox
)

// Flow identifies which intake an attack arrived through. Severity is recorded
// in the defense buffer; flow + kind together let the UI show the per-channel
// breakdown.
type Flow int

const (
	BlockFlow Flow = iota
	ChatFlow
)

// AttackKind enumerates the hostile-input classes the recorder routes into a
// per-flow counter. Chat has no signature and no PoW, so BadSignature and
// ForgedWork are silently ignored under ChatFlow (a defensive shape, not a
// caller error).
type AttackKind int

const (
	Injection AttackKind = iota
	BadSignature
	ForgedWork
	Malformed
)

// Recorder owns the in-memory counters. NewRecorder allocates a zeroed
// recorder; tests compare snapshots to verify each Record/RecordAttack call
// lands on the right counter.
type Recorder struct {
	sendsOK          atomic.Uint64
	sendsFail        atomic.Uint64
	egressThrottled  atomic.Uint64
	defenseThrottled atomic.Uint64
	coldSkipped      atomic.Uint64
	blocksReceived   atomic.Uint64
	chatReceived     atomic.Uint64

	blockInjection    atomic.Uint64
	blockBadSignature atomic.Uint64
	blockForgedWork   atomic.Uint64
	blockMalformed    atomic.Uint64
	chatInjection     atomic.Uint64
	chatMalformed     atomic.Uint64
}

// NewRecorder builds a recorder with every counter at zero.
func NewRecorder() *Recorder { return &Recorder{} }

// Record routes one event to its counter and returns the counter's new value
// (atomic.Uint64.Add already gives us this for free). An unknown event value
// (a constant added upstream the recorder has not learned about yet) is a no-
// op and returns 0 — so a 0 return doubles as an "unrecognized event" signal
// the caller can assert on in tests.
func (r *Recorder) Record(event Event) uint64 {
	switch event {
	case SendOK:
		return r.sendsOK.Add(1)
	case SendFail:
		return r.sendsFail.Add(1)
	case EgressThrottled:
		return r.egressThrottled.Add(1)
	case DefenseThrottled:
		return r.defenseThrottled.Add(1)
	case ColdSkipped:
		return r.coldSkipped.Add(1)
	case BlockReceived:
		return r.blocksReceived.Add(1)
	case ChatReceived:
		return r.chatReceived.Add(1)
	}
	return 0
}

// RecordAttack routes one hostile-input event to its (flow, kind) counter and
// returns the counter's new value. (ChatFlow, BadSignature) and (ChatFlow,
// ForgedWork) are deliberately dropped — chat envelopes carry neither
// signature nor PoW — so the caller cannot legitimately produce those
// combinations and the return is 0 if they try.
func (r *Recorder) RecordAttack(flow Flow, kind AttackKind) uint64 {
	switch flow {
	case BlockFlow:
		switch kind {
		case Injection:
			return r.blockInjection.Add(1)
		case BadSignature:
			return r.blockBadSignature.Add(1)
		case ForgedWork:
			return r.blockForgedWork.Add(1)
		case Malformed:
			return r.blockMalformed.Add(1)
		}
	case ChatFlow:
		switch kind {
		case Injection:
			return r.chatInjection.Add(1)
		case Malformed:
			return r.chatMalformed.Add(1)
		}
	}
	return 0
}

// Snapshot is the JSON shape persisted to disk. Field names match the
// counters the user asked for (sends_ok, sends_fail, peers_cold,
// attacks_by_flow, throttle_events) so the Android UI consumes the file
// with no mapping. With flow isolation (Opt 5) we track separate defense
// levels for blocks and chat: BlockDefenseLevel and ChatDefenseLevel.
// CycleMs is the duration the mesh loop is sleeping between ticks RIGHT NOW
// (post-adaptive-stretch): equal to base_cycle_ms at rest, up to 2× base
// under sustained pressure. The cycle stretches by max(block, chat) level.
type Snapshot struct {
	UpdatedAt         string         `json:"updated_at"`
	SendsOK           uint64         `json:"sends_ok"`
	SendsFail         uint64         `json:"sends_fail"`
	PeersCold         int            `json:"peers_cold"`
	PeersKnown        int            `json:"peers_known"`
	ThrottleEvents    ThrottleCounts `json:"throttle_events"`
	AttacksByFlow     AttacksByFlow  `json:"attacks_by_flow"`
	Receives          ReceiveCounts  `json:"receives"`
	BlockDefenseLevel float64        `json:"block_defense_level"`
	ChatDefenseLevel  float64        `json:"chat_defense_level"`
	BaseCycleMs       int64          `json:"base_cycle_ms"`
	CycleMs           int64          `json:"cycle_ms"`
}

// ThrottleCounts groups the three throttle reasons so the UI shows WHY a send
// did not go out (egress shaping, defense gate, or cold-peer skip).
type ThrottleCounts struct {
	Egress         uint64 `json:"egress"`
	IngressDefense uint64 `json:"ingress_defense"`
	ColdSkipped    uint64 `json:"cold_skipped"`
}

// AttacksByFlow is the per-flow attack breakdown.
type AttacksByFlow struct {
	Blocks BlockAttackKinds `json:"blocks"`
	Chat   ChatAttackKinds  `json:"chat"`
}

// BlockAttackKinds enumerates the kinds the block intake classifies.
type BlockAttackKinds struct {
	Injection    uint64 `json:"injection"`
	BadSignature uint64 `json:"bad_signature"`
	ForgedWork   uint64 `json:"forged_work"`
	Malformed    uint64 `json:"malformed"`
}

// ChatAttackKinds enumerates the kinds the chat intake classifies (no
// signature, no PoW on chat).
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
// dynamic state (cold/known peer counts, defense levels for blocks and chat,
// base + current cycle in milliseconds) so the file reflects both monotonic
// activity and the live system snapshot at this moment. With flow isolation
// we track blockDefenseLevel and chatDefenseLevel separately. baseCycle is
// the configured baseline; currentCycle is the value after
// defense.AdaptiveCycle has stretched it for this tick — they're equal at
// rest and diverge under pressure.
func (r *Recorder) Snapshot(peersCold, peersKnown int, blockDefenseLevel, chatDefenseLevel float64, baseCycle, currentCycle time.Duration) Snapshot {
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
		BlockDefenseLevel: blockDefenseLevel,
		ChatDefenseLevel:  chatDefenseLevel,
		BaseCycleMs:       baseCycle.Milliseconds(),
		CycleMs:           currentCycle.Milliseconds(),
	}
}

// PersistTo writes a snapshot to path atomically (temp file + fsync + rename,
// via paths.WriteFileAtomic). A half-written stats file is impossible — a
// reader sees either the previous version or the new one. With flow isolation,
// blockDefenseLevel and chatDefenseLevel are persisted separately.
func (r *Recorder) PersistTo(path string, peersCold, peersKnown int, blockDefenseLevel, chatDefenseLevel float64, baseCycle, currentCycle time.Duration) error {
	data, err := json.MarshalIndent(r.Snapshot(peersCold, peersKnown, blockDefenseLevel, chatDefenseLevel, baseCycle, currentCycle), "", "  ")
	if err != nil {
		return err
	}
	return paths.WriteFileAtomic(path, data, 0o600)
}
