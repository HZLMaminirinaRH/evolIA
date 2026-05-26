package mesh

import (
	"encoding/json"
	"errors"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"evolia/defense"
	"evolia/pow"
)

func TestStoreIncomingWritesAndCounts(t *testing.T) {
	dir := t.TempDir()
	name, params, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":4.5}`), nil, nil)
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
	if _, _, err := StoreIncoming(dir, []byte(`not json`), nil, nil); !errors.Is(err, ErrMalformed) {
		t.Fatalf("want ErrMalformed for invalid json, got %v", err)
	}
	if _, _, err := StoreIncoming(dir, []byte(`{"v_value":1}`), nil, nil); !errors.Is(err, ErrMalformed) {
		t.Fatalf("want ErrMalformed for missing device_id, got %v", err)
	}
}

func TestStoreIncomingInjectionRejected(t *testing.T) {
	dir := t.TempDir()
	_, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1'; DROP TABLE peers;--","v_value":1}`), nil, nil)
	if !errors.Is(err, ErrInjection) {
		t.Fatalf("want ErrInjection, got %v", err)
	}
}

func TestStoreIncomingSignature(t *testing.T) {
	dir := t.TempDir()
	key := []byte("fleet-secret")

	// A correctly signed block, carrying a valid proof of work (screen_input×40
	// at v=0 yields exactly 2.0), is accepted and its params returned.
	signed, _ := json.Marshal(Block{
		Device: "d1",
		VValue: 2.0,
		Sig:    SignBlock(key, "d1", 2.0),
		Params: map[string]float64{"ALPHA": 0.3},
		Work:   &pow.WorkProof{VPrev: 0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5},
	})
	name, params, err := StoreIncoming(dir, signed, key, nil)
	if err != nil {
		t.Fatalf("signed block must be accepted, got %v", err)
	}
	if name != "recv_d1.json" || params["ALPHA"] != 0.3 {
		t.Fatalf("unexpected name/params: %q %v", name, params)
	}

	// A missing/forged signature is rejected when a key is configured.
	bad, _ := json.Marshal(Block{Device: "d2", VValue: 2.0, Sig: "deadbeef"})
	if _, _, err := StoreIncoming(dir, bad, key, nil); !errors.Is(err, ErrBadSignature) {
		t.Fatalf("want ErrBadSignature, got %v", err)
	}
}

func TestStoreIncomingProofOfWork(t *testing.T) {
	dir := t.TempDir()
	key := []byte("fleet-secret")
	mk := func(device string, v float64, w *pow.WorkProof) []byte {
		data, _ := json.Marshal(Block{Device: device, VValue: v, Sig: SignBlock(key, device, v), Work: w})
		return data
	}

	// Honest first increment: 0 -> 2.0 (screen_input×40 at v=0).
	if _, _, err := StoreIncoming(dir, mk("d1", 2.0, &pow.WorkProof{VPrev: 0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5}), key, nil); err != nil {
		t.Fatalf("honest work must be accepted: %v", err)
	}
	// Fabricated value with trivial declared work is rejected and scored.
	if _, _, err := StoreIncoming(dir, mk("d1", 1000, &pow.WorkProof{VPrev: 2.0, Actions: map[string]int{"screen_input": 1}, V: 0, Dt: 5}), key, nil); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("fabricated value must be ErrForgedWork, got %v", err)
	}
	// A keyed block carrying no proof at all is rejected (no omit-to-bypass).
	noproof, _ := json.Marshal(Block{Device: "d1", VValue: 3.0, Sig: SignBlock(key, "d1", 3.0)})
	if _, _, err := StoreIncoming(dir, noproof, key, nil); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("keyed block without proof must be ErrForgedWork, got %v", err)
	}
	// Honest chained increment: 2.0 -> 4.5 (photo_taken×1 at v=0).
	if _, _, err := StoreIncoming(dir, mk("d1", 4.5, &pow.WorkProof{VPrev: 2.0, Actions: map[string]int{"photo_taken": 1}, V: 0, Dt: 5}), key, nil); err != nil {
		t.Fatalf("honest chained increment must be accepted: %v", err)
	}
	if got := TotalV(dir); got != 4.5 {
		t.Fatalf("want 4.5 after chained increments, got %v", got)
	}
	// Replay of an old value does not advance our record: stale, value unchanged.
	if _, _, err := StoreIncoming(dir, mk("d1", 2.0, &pow.WorkProof{VPrev: 0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5}), key, nil); !errors.Is(err, ErrStale) {
		t.Fatalf("stale replay must be ErrStale, got %v", err)
	}
	if got := TotalV(dir); got != 4.5 {
		t.Fatalf("stale replay must not change the stored value, got %v", got)
	}
}

func TestStoreIncomingOverwritesPerDevice(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":1.0}`), nil, nil); err != nil {
		t.Fatal(err)
	}
	if _, _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":9.0}`), nil, nil); err != nil {
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

func TestStoreIncomingGenesisCeilingBoundsBaseline(t *testing.T) {
	// A brand-new fleet (genesis = now): the ceiling is ~72·300 = 21600, so an
	// inflated trust-on-first-use baseline is forged even though the declared
	// increment itself reconciles — closing the v1 TOFU minting hole.
	t.Setenv("EVOLIA_GENESIS_UNIX", strconv.FormatInt(time.Now().Unix(), 10))

	liar, _ := json.Marshal(Block{
		Device: "liar", VValue: 1_000_000,
		Work: &pow.WorkProof{VPrev: 1_000_000 - 2.0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5},
	})
	if _, _, err := StoreIncoming(t.TempDir(), liar, nil, nil); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("inflated TOFU baseline must be forged under the genesis ceiling, got %v", err)
	}

	honest, _ := json.Marshal(Block{
		Device: "honest", VValue: 2.0,
		Work: &pow.WorkProof{VPrev: 0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5},
	})
	if _, _, err := StoreIncoming(t.TempDir(), honest, nil, nil); err != nil {
		t.Fatalf("honest first claim under the ceiling must be accepted, got %v", err)
	}
}

func TestStoreIncomingEvolutiveCeilingTightens(t *testing.T) {
	// Old fleet: the physical ceiling is large (~72M), so the baseline below is
	// physically admissible while calm — but the evolutive defense tightens it.
	t.Setenv("EVOLIA_GENESIS_UNIX", strconv.FormatInt(time.Now().Unix()-1_000_000, 10))
	block := func() []byte {
		data, _ := json.Marshal(Block{
			Device: "newpeer", VValue: 30_000_000,
			Work: &pow.WorkProof{VPrev: 30_000_000 - 2.0, Actions: map[string]int{"screen_input": 40}, V: 0, Dt: 5},
		})
		return data
	}

	// Calm: 30M is under the ~72M physical ceiling -> accepted.
	if _, _, err := StoreIncoming(t.TempDir(), block(), nil, defense.New(64)); err != nil {
		t.Fatalf("calm: baseline under the physical ceiling must be accepted, got %v", err)
	}

	// Under sustained forged-work pressure the admissible ceiling contracts to
	// ~0.25x (~18M), so the very same baseline is now rejected as forged: the PoW
	// arm of the evolutive defense (D_evo) hardens what value a block may assert.
	hot := defense.New(64)
	for i := 0; i < 12; i++ {
		hot.Record(defense.ForgedWork)
	}
	if _, _, err := StoreIncoming(t.TempDir(), block(), nil, hot); !errors.Is(err, ErrForgedWork) {
		t.Fatalf("under defense pressure the tightened ceiling must reject, got %v", err)
	}
}

func TestStoreIncomingSanitizesDeviceID(t *testing.T) {
	dir := t.TempDir()
	name, _, err := StoreIncoming(dir, []byte(`{"device_id":"a-b.c:1","v_value":2}`), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if name != "recv_a-b_c_1.json" {
		t.Fatalf("want recv_a-b_c_1.json, got %q", name)
	}
}
