// Package chat is the opaque transport layer for evolIA's end-to-end peer
// messaging.
//
// The end-to-end cryptography lives in the app (the Kotlin ChatIdentity): a
// message body is sealed and authenticated there and is OPAQUE to this layer —
// the relay never decrypts it and holds no key. Go's only job is routing: drain
// the app's outbox, carry each envelope to peers, and deliver inbound envelopes
// addressed to this node into the app's inbox. This mirrors the file-based
// interop of the rest of the system: an append-only outbox drained by a single
// relay (like evolia_action_queue.jsonl), and an append-only inbox the app reads.
//
// Phase 1 rides the existing UDP mesh transport (best-effort delivery). Hostile
// chat input (malformed datagrams, injection-like fingerprints) is classified so
// it feeds the same adaptive defense as block input.
package chat

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"strings"

	"evolia/defense"
)

// maxBodyBytes bounds an opaque body: a chat line, not a file transfer.
const maxBodyBytes = 16 * 1024

// MaxDatagramBytes is the receive-buffer/parse cap for an incoming chat
// datagram (routing fields + the bounded body, with headroom).
const MaxDatagramBytes = 32 * 1024

// Classified intake errors so the defense layer can score the right attack.
var (
	ErrMalformed = errors.New("malformed chat message")
	ErrInjection = errors.New("injection-like chat field")
	ErrTooLarge  = errors.New("chat message too large")
)

// Message is the routing envelope around an opaque end-to-end body. Go reads
// To/From (chat fingerprints) only to route; it never inspects or decrypts Body.
type Message struct {
	ID   string `json:"id"`
	To   string `json:"to"`
	From string `json:"from"`
	TS   string `json:"ts"`
	Body string `json:"body"`
}

// Wire serializes a message for the transport.
func (m Message) Wire() []byte {
	data, _ := json.Marshal(m)
	return data
}

// ParseIncoming validates a received datagram into a Message: every routing
// field must be present, the body is size-capped, and injection-like fingerprints
// are rejected — so hostile chat input is classified like block input and can
// feed the adaptive defense.
func ParseIncoming(data []byte) (Message, error) {
	if len(data) > MaxDatagramBytes {
		return Message{}, ErrTooLarge
	}
	var m Message
	if err := json.Unmarshal(data, &m); err != nil {
		return Message{}, ErrMalformed
	}
	if m.ID == "" || m.To == "" || m.From == "" || m.Body == "" {
		return Message{}, ErrMalformed
	}
	if len(m.Body) > maxBodyBytes {
		return Message{}, ErrTooLarge
	}
	if defense.LooksLikeInjection(m.ID) || defense.LooksLikeInjection(m.To) || defense.LooksLikeInjection(m.From) {
		return Message{}, ErrInjection
	}
	return m, nil
}

// AddressedTo reports whether an inbound message is for this node. An empty
// myFP (no identity advertised yet) accepts everything — useful single-device
// and in tests; once the app has written its fingerprint, only exact matches
// are kept (Phase 1 is direct LAN delivery, no store-and-forward).
func AddressedTo(m Message, myFP string) bool {
	return myFP == "" || m.To == myFP
}

// DrainOutbox atomically takes the app's outbox aside and returns its queued
// messages, so the single relay reads each line exactly once — the same
// rename-aside pattern as evolia_actions.drain. A missing outbox yields no
// messages and no error. Unparseable lines are skipped.
func DrainOutbox(outboxPath string) ([]Message, error) {
	tmp := outboxPath + ".draining"
	if err := os.Rename(outboxPath, tmp); err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	defer os.Remove(tmp)

	f, err := os.Open(tmp)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	var out []Message
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), MaxDatagramBytes)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var m Message
		if json.Unmarshal(line, &m) == nil && m.ID != "" {
			out = append(out, m)
		}
	}
	return out, sc.Err()
}

// AppendInbox appends an inbound message to the app's inbox, deduped by id via
// the caller-held seen set (so a re-sent datagram is stored once). Returns
// whether it was newly written. The seen set is the relay's; preload it from an
// existing inbox with LoadSeenIDs so dedup survives a restart.
func AppendInbox(inboxPath string, m Message, seen map[string]bool) (bool, error) {
	if seen != nil && seen[m.ID] {
		return false, nil
	}
	f, err := os.OpenFile(inboxPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return false, err
	}
	defer f.Close()
	if _, err := f.Write(append(m.Wire(), '\n')); err != nil {
		return false, err
	}
	if seen != nil {
		seen[m.ID] = true
	}
	return true, nil
}

// LoadSeenIDs reads the message ids already in the inbox, so the relay can
// preload its dedup set on startup and not re-append a message it already
// delivered before a restart. A missing inbox yields an empty set.
func LoadSeenIDs(inboxPath string) map[string]bool {
	seen := map[string]bool{}
	f, err := os.Open(inboxPath)
	if err != nil {
		return seen
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), MaxDatagramBytes)
	for sc.Scan() {
		var m Message
		if json.Unmarshal(sc.Bytes(), &m) == nil && m.ID != "" {
			seen[m.ID] = true
		}
	}
	return seen
}

// LoadFingerprint reads this node's chat-identity fingerprint (written by the
// app) for inbound routing. Empty when absent — AddressedTo then accepts all.
func LoadFingerprint(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}
