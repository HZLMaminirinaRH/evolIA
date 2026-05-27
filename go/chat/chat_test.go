package chat

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func sample() Message {
	return Message{ID: "m1", To: "bobfp", From: "alicefp", TS: "2026-05-27T00:00:00Z", Body: "<<opaque-sealed-envelope>>"}
}

func TestParseIncomingRoundtrip(t *testing.T) {
	m := sample()
	got, err := ParseIncoming(m.Wire())
	if err != nil {
		t.Fatalf("parse valid: %v", err)
	}
	if got != m {
		t.Fatalf("roundtrip mismatch: %+v != %+v", got, m)
	}
}

func TestParseIncomingRejections(t *testing.T) {
	cases := []struct {
		name string
		data []byte
		want error
	}{
		{"not json", []byte("{not json"), ErrMalformed},
		{"missing fields", Message{ID: "x"}.Wire(), ErrMalformed},
		{"injection to", Message{ID: "1", To: "a' OR '1'='1", From: "f", Body: "b"}.Wire(), ErrInjection},
		{"body too large", Message{ID: "1", To: "t", From: "f", Body: strings.Repeat("a", maxBodyBytes+1)}.Wire(), ErrTooLarge},
		{"datagram too large", make([]byte, MaxDatagramBytes+1), ErrTooLarge},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if _, err := ParseIncoming(c.data); !errors.Is(err, c.want) {
				t.Fatalf("got %v, want %v", err, c.want)
			}
		})
	}
}

func TestAddressedTo(t *testing.T) {
	m := sample()
	if !AddressedTo(m, "") {
		t.Fatal("empty fingerprint should accept all")
	}
	if !AddressedTo(m, "bobfp") {
		t.Fatal("matching fingerprint should accept")
	}
	if AddressedTo(m, "carolfp") {
		t.Fatal("non-matching fingerprint should drop")
	}
}

func TestDrainOutbox(t *testing.T) {
	dir := t.TempDir()
	outbox := filepath.Join(dir, "outbox.jsonl")

	// Missing outbox => empty, no error.
	if got, err := DrainOutbox(outbox); err != nil || got != nil {
		t.Fatalf("missing outbox: got %v err %v", got, err)
	}

	m1, m2 := sample(), sample()
	m2.ID = "m2"
	body := append(append(m1.Wire(), '\n'), append(m2.Wire(), '\n')...)
	if err := os.WriteFile(outbox, body, 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := DrainOutbox(outbox)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].ID != "m1" || got[1].ID != "m2" {
		t.Fatalf("drain: %+v", got)
	}
	// Drained file is consumed: a second drain is empty.
	if again, _ := DrainOutbox(outbox); len(again) != 0 {
		t.Fatalf("second drain should be empty, got %+v", again)
	}
	if _, err := os.Stat(outbox); !os.IsNotExist(err) {
		t.Fatal("outbox should be gone after drain")
	}
}

func TestAppendInboxDedup(t *testing.T) {
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox.jsonl")
	seen := map[string]bool{}
	m := sample()

	if ok, err := AppendInbox(inbox, m, seen); err != nil || !ok {
		t.Fatalf("first append: ok=%v err=%v", ok, err)
	}
	if ok, _ := AppendInbox(inbox, m, seen); ok {
		t.Fatal("duplicate id should not be re-appended")
	}

	data, _ := os.ReadFile(inbox)
	if n := strings.Count(strings.TrimSpace(string(data)), "\n"); n != 0 {
		t.Fatalf("expected exactly one line, got %d extra newlines", n)
	}
}

func TestLoadSeenIDsAndFingerprint(t *testing.T) {
	dir := t.TempDir()
	inbox := filepath.Join(dir, "inbox.jsonl")
	m := sample()
	if _, err := AppendInbox(inbox, m, nil); err != nil {
		t.Fatal(err)
	}
	seen := LoadSeenIDs(inbox)
	if !seen[m.ID] {
		t.Fatal("preloaded seen set should contain the inbox id")
	}
	// A relay that preloads seen will not re-append the same message.
	if ok, _ := AppendInbox(inbox, m, seen); ok {
		t.Fatal("preloaded seen should dedup across restart")
	}

	fp := filepath.Join(dir, "fp.txt")
	if LoadFingerprint(fp) != "" {
		t.Fatal("absent fingerprint should be empty")
	}
	_ = os.WriteFile(fp, []byte("  myfingerprint\n"), 0o600)
	if LoadFingerprint(fp) != "myfingerprint" {
		t.Fatal("fingerprint should be trimmed")
	}
}
