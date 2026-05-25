package mesh

import (
	"path/filepath"
	"testing"
)

func TestStoreIncomingWritesAndCounts(t *testing.T) {
	dir := t.TempDir()
	name, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":4.5}`))
	if err != nil {
		t.Fatal(err)
	}
	if name != "recv_d1.json" {
		t.Fatalf("want recv_d1.json, got %q", name)
	}
	if got := TotalV(dir); got != 4.5 {
		t.Fatalf("want TotalV 4.5, got %v", got)
	}
}

func TestStoreIncomingRejects(t *testing.T) {
	dir := t.TempDir()
	if _, err := StoreIncoming(dir, []byte(`not json`)); err == nil {
		t.Fatal("want error for invalid json")
	}
	if _, err := StoreIncoming(dir, []byte(`{"v_value":1}`)); err == nil {
		t.Fatal("want error for missing device_id")
	}
}

func TestStoreIncomingOverwritesPerDevice(t *testing.T) {
	dir := t.TempDir()
	if _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":1.0}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := StoreIncoming(dir, []byte(`{"device_id":"d1","v_value":9.0}`)); err != nil {
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
	name, err := StoreIncoming(dir, []byte(`{"device_id":"a/b.c:1","v_value":2}`))
	if err != nil {
		t.Fatal(err)
	}
	if name != "recv_a_b_c_1.json" {
		t.Fatalf("want recv_a_b_c_1.json, got %q", name)
	}
}
