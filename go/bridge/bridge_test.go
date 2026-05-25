package bridge

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"evolia/defense"
	"evolia/mesh"
	"evolia/paths"
)

func TestFuseParamsRenormalizes(t *testing.T) {
	local := map[string]float64{"A": 0.5, "B": 0.5}
	incoming := map[string]float64{"A": 1.0, "B": 0.0}
	fused := FuseParams(local, incoming)

	var sum float64
	for _, v := range fused {
		sum += v
	}
	if math.Abs(sum-1.0) > 1e-9 {
		t.Fatalf("fused params should sum to 1, got %v", sum)
	}
	// A got more weight from incoming, so A > B after fusion.
	if fused["A"] <= fused["B"] {
		t.Fatalf("expected A > B, got %v", fused)
	}
}

func TestFuseParamsMissingIncomingKeepsLocal(t *testing.T) {
	local := map[string]float64{"A": 1.0}
	fused := FuseParams(local, map[string]float64{})
	if math.Abs(fused["A"]-1.0) > 1e-9 {
		t.Fatalf("single key should normalize to 1, got %v", fused)
	}
}

func TestHealthEndpoint(t *testing.T) {
	srv := NewServer("dev-1", t.TempDir(), nil, nil)
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	var body map[string]any
	json.Unmarshal(rec.Body.Bytes(), &body)
	if body["device"] != "dev-1" {
		t.Fatalf("unexpected body: %v", body)
	}
}

func TestBlockStoredAndCounted(t *testing.T) {
	t.Setenv("EVOLIA_HOME", t.TempDir())
	vault := paths.MeshVault()
	srv := NewServer("dev-1", vault, nil, nil)

	req := httptest.NewRequest(http.MethodPost, "/block",
		strings.NewReader(`{"source_device":"peerX","v_value":4.5,"cognitive_params":{"ALPHA":0.5}}`))
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /block status = %d body=%s", rec.Code, rec.Body.String())
	}

	// mesh/total_v should now reflect the stored block.
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/mesh/total_v", nil))
	var body map[string]float64
	json.Unmarshal(rec2.Body.Bytes(), &body)
	if math.Abs(body["mesh_total_v"]-4.5) > 1e-9 {
		t.Fatalf("mesh_total_v = %v, want 4.5", body["mesh_total_v"])
	}
}

func TestBridgeDefenseHardensOnHostileInput(t *testing.T) {
	t.Setenv("EVOLIA_HOME", t.TempDir())
	key := []byte("fleet-secret")
	def := defense.New(64)
	srv := NewServer("dev-1", paths.MeshVault(), key, def)

	post := func(body string) int {
		rec := httptest.NewRecorder()
		srv.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/block", strings.NewReader(body)))
		return rec.Code
	}

	// Injection-like source device is rejected and raises the defense.
	if code := post(`{"source_device":"x'; DROP TABLE peers;--","v_value":1}`); code != http.StatusBadRequest {
		t.Fatalf("injection: want 400, got %d", code)
	}
	// A forged/missing signature is rejected (a key is configured).
	if code := post(`{"source_device":"peerX","v_value":4.5}`); code != http.StatusUnauthorized {
		t.Fatalf("bad sig: want 401, got %d", code)
	}
	if def.Level() <= 0 {
		t.Fatalf("defense must have risen after hostile input, got %v", def.Level())
	}

	// A correctly signed block is accepted.
	good := `{"source_device":"peerX","v_value":4.5,"sig":"` + mesh.SignBlock(key, "peerX", 4.5) + `"}`
	if code := post(good); code != http.StatusOK {
		t.Fatalf("signed block: want 200, got %d", code)
	}
}

func TestBlockRejectsBadInput(t *testing.T) {
	srv := NewServer("dev-1", t.TempDir(), nil, nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/block", strings.NewReader(`{}`)))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty block, got %d", rec.Code)
	}

	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/block", nil))
	if rec2.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405 for GET /block, got %d", rec2.Code)
	}
}
