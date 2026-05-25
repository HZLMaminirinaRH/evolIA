package mesh

import (
	"encoding/json"
	"errors"
	"path/filepath"
	"testing"
)

func TestStoreIncomingWritesAndCounts(t *testing.T) {
	dir := t.TempDir()
	name, params, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":4.5}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if name != "recv_d1.json" {
		t.Fatalf("want recv_d1.json, got %q", name)
	}
	if params != nil {
		t.Fatalf("want nil params, got %v", params)
	}
	if got := TotalV(dir); got != 4.5 {
		t.Fatalf("want TotalV 4.5, got %v", got)
	}
}

func TestStoreIncomingRejects(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := StoreIncoming(dir, []byte(`not json`), nil); !errors.Is(err, ErrMalformed) {
		t.Fatalf("want ErrMalformed for invalid json, got %v", err)
	}
	if _, _, err := StoreIncoming(dir, []byte(`{"v_value":1}`), nil); !errors.Is(err, ErrMalformed) {
		t.Fatalf("want ErrMalformed for missing device_id, got %v", err)
	}
}

func TestStoreIncomingInjectionRejected(t *testing.T) {
	dir := t.TempDir()
	_, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1'; DROP TABLE peers;--","v_value":1}`), nil)
	if !errors.Is(err, ErrInjection) {
		t.Fatalf("want ErrInjection, got %v", err)
	}
}

func TestStoreIncomingSignature(t *testing.T) {
	dir := t.TempDir()
	key := []byte("fleet-secret")

	// A correctly signed block is accepted and its params returned.
	signed, _ := json.Marshal(Block{
		Device: "d1",
		VValue: 2.0,
		Sig:    SignBlock(key, "d1", 2.0),
		Params: map[string]float64{"ALPHA": 0.3},
	})
	name, params, err := StoreIncoming(dir, signed, key)
	if err != nil {
		t.Fatalf("signed block must be accepted, got %v", err)
	}
	if name != "recv_d1.json" || params["ALPHA"] != 0.3 {
		t.Fatalf("unexpected name/params: %q %v", name, params)
	}

	// A missing/forged signature is rejected when a key is configured.
	bad, _ := json.Marshal(Block{Device: "d2", VValue: 2.0, Sig: "deadbeef"})
	if _, _, err := StoreIncoming(dir, bad, key); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("want ErrBadSignature, got %v", err)
	}
}

func TestStoreIncomingOverwritesPerDevice(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":1.0}`), nil); err != nil {
		t.Fatal(err)
	}
	if _, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":9.0}`), nil); err != nil {
		t.Fatal(err)
	}
	matches, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	if len(matches) != 1 {
		t.Fatalf("want 1 file after re-send (overwrite), got %d", len(matches))
	}
	if got := TotalV(dir); got != 9.0 {
		t.Fatalf("want latest value 9.0, got %v", got)
	}
}

func TestStoreIncomingSanitizesDeviceID(t *testing.T) {
	dir := t.TempDir()
	name, _, err := StoreIncoming(dir, []byte(`{"device_id":"a-b.c:1","v_value":2}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if name != "recv_a-b_c_1.json" {
		t.Fatalf("want recv_a-b_c_1.json, got %q", name)
	}
}
