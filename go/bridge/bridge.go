// Package bridge is the peer-to-peer block exchange service for evolIA.
//
// It is the cleaned-up successor of the original bridge.py: peers POST their V
// blocks here over HTTP, the block is stored in the shared mesh vault (so
// mesh-sync and the dashboard see it), and the incoming cognitive parameters
// are fused into the local set. No "infection" semantics — just an explicit,
// auditable block intake over standard-library HTTP.
package bridge

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"time"

	"evolia/defense"
	"evolia/mesh"
	"evolia/paths"
	"evolia/pow"
)

// DefaultParams is the starting cognitive-parameter set when none is stored.
var DefaultParams = map[string]float64{
	"ALPHA":   0.20,
	"BETA":    0.15,
	"GAMMA":   0.20,
	"DELTA":   0.20,
	"EPSILON": 0.25,
}

// Block is a V block received from a peer.
type Block struct {
	SourceDevice    string             `json:"source_device"`
	VValue          float64            `json:"v_value"`
	CognitiveParams map[string]float64 `json:"cognitive_params"`
	Sig             string             `json:"sig,omitempty"`
	Work            *pow.WorkProof     `json:"work,omitempty"`
	Timestamp       string             `json:"timestamp"`
}

// FuseIncoming blends incoming cognitive params into the local set and saves.
// Exported so mesh-sync can fuse params carried over UDP, not just the bridge.
func FuseIncoming(incoming map[string]float64) {
	if len(incoming) == 0 {
		return
	}
	saveParams(FuseParams(loadLocalParams(), incoming))
}

// FuseParams blends local and incoming params (0.7 local + 0.3 incoming) and
// renormalizes so the result sums to 1. Missing incoming keys keep the local
// value; the key set is taken from local.
func FuseParams(local, incoming map[string]float64) map[string]float64 {
	fused := make(map[string]float64, len(local))
	var total float64
	for k, lv := range local {
		iv, ok := incoming[k]
		if !ok {
			iv = lv
		}
		v := 0.7*lv + 0.3*iv
		fused[k] = v
		total += v
	}
	if total > 0 {
		for k := range fused {
			fused[k] /= total
		}
	}
	return fused
}

func loadLocalParams() map[string]float64 {
	if data, err := os.ReadFile(paths.CognitiveParams()); err == nil {
		var m map[string]float64
		if json.Unmarshal(data, &m) == nil && len(m) > 0 {
			return m
		}
	}
	out := make(map[string]float64, len(DefaultParams))
	for k, v := range DefaultParams {
		out[k] = v
	}
	return out
}

func saveParams(m map[string]float64) {
	if data, err := json.MarshalIndent(m, "", "  "); err == nil {
		_ = paths.WriteFileAtomic(paths.CognitiveParams(), data, 0o600)
	}
}

// StoreBlock writes a peer block into the mesh vault, keyed by device id so
// repeated posts from the same peer overwrite rather than accumulate (shared
// with the UDP intake path, so TotalV never double-counts a peer).
func StoreBlock(vault, device string, vValue float64, work *pow.WorkProof) (string, error) {
	return mesh.StorePeerBlock(vault, device, vValue, work)
}

// checkWork applies the proof-of-work policy to an HTTP value claim: a keyed
// fleet must carry a proof, and any proof present must validate. It records a
// ForgedWork attack on rejection. Returns an HTTP status to send (0 = proceed).
func checkWork(vault, device string, newV float64, work *pow.WorkProof, key []byte, def *defense.AdaptiveDefense, w http.ResponseWriter) bool {
	if len(key) > 0 && work == nil {
		lvl := def.Record(defense.ForgedWork)
		writeJSON(w, http.StatusForbidden, map[string]any{"status": "rejected", "reason": "missing proof of work", "defense": lvl})
		return false
	}
	if work != nil {
		storedV := mesh.StoredV(vault, device)
		if err := pow.ValidateBlock(storedV, newV, *work, mesh.AdmissibleCeiling(storedV, def.Level())); err != nil {
			if errors.Is(err, pow.ErrStale) {
				writeJSON(w, http.StatusOK, map[string]any{"status": "stale"})
				return false
			}
			lvl := def.Record(defense.ForgedWork)
			writeJSON(w, http.StatusForbidden, map[string]any{"status": "rejected", "reason": "forged work", "defense": lvl})
			return false
		}
	}
	return true
}

func clientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// NewServer builds the HTTP handler for the bridge. With a non-nil key, posted
// blocks must carry a valid HMAC signature; the def buffer hardens the bridge as
// it absorbs injection/forged/malformed input.
func NewServer(deviceID, vault string, key []byte, def *defense.AdaptiveDefense) *http.ServeMux {
	if def == nil {
		def = defense.New(64)
	}
	// The intake throttle hardens as the defense absorbs attacks: under a flood
	// it squeezes each source toward a floor; it relaxes as the buffer decays.
	gate := defense.NewGate(def, time.Now)
	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{
			"status": "ok", "device": deviceID, "defense": def.Level(),
		})
	})

	mux.HandleFunc("/defense", func(w http.ResponseWriter, r *http.Request) {
		lvl := def.Level()
		writeJSON(w, http.StatusOK, map[string]any{
			"defense_level": lvl, "buffered": def.Len(), "throttle_pressure": defense.Pressure(lvl),
		})
	})

	mux.HandleFunc("/mesh/total_v", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"mesh_total_v": mesh.TotalV(vault)})
	})

	mux.HandleFunc("/block", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"status": "error"})
			return
		}
		if !gate.Allow(clientIP(r)) {
			writeJSON(w, http.StatusTooManyRequests, map[string]any{"status": "throttled", "reason": "defense pressure"})
			return
		}
		var b Block
		if err := json.NewDecoder(r.Body).Decode(&b); err != nil || b.SourceDevice == "" {
			def.Record(defense.Malformed)
			writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "reason": "invalid block"})
			return
		}
		if defense.LooksLikeInjection(b.SourceDevice) {
			lvl := def.Record(defense.SQLInjection)
			writeJSON(w, http.StatusBadRequest, map[string]any{"status": "rejected", "reason": "injection", "defense": lvl})
			return
		}
		if len(key) > 0 && !mesh.VerifyBlock(key, b.SourceDevice, b.VValue, b.Sig) {
			lvl := def.Record(defense.BadSignature)
			writeJSON(w, http.StatusUnauthorized, map[string]any{"status": "rejected", "reason": "bad signature", "defense": lvl})
			return
		}
		if !checkWork(vault, b.SourceDevice, b.VValue, b.Work, key, def, w) {
			return
		}
		name, err := StoreBlock(vault, b.SourceDevice, b.VValue, b.Work)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "reason": err.Error()})
			return
		}
		if len(b.CognitiveParams) > 0 {
			saveParams(FuseParams(loadLocalParams(), b.CognitiveParams))
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "stored", "file": name, "source": b.SourceDevice})
	})

	mux.HandleFunc("/sync", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"status": "error"})
			return
		}
		if !gate.Allow(clientIP(r)) {
			writeJSON(w, http.StatusTooManyRequests, map[string]any{"status": "throttled", "reason": "defense pressure"})
			return
		}
		var s struct {
			DeviceID string         `json:"device_id"`
			TotalV   float64        `json:"total_v"`
			Sig      string         `json:"sig,omitempty"`
			Work     *pow.WorkProof `json:"work,omitempty"`
		}
		if err := json.NewDecoder(r.Body).Decode(&s); err != nil || s.DeviceID == "" {
			def.Record(defense.Malformed)
			writeJSON(w, http.StatusBadRequest, map[string]any{"status": "error", "reason": "invalid sync"})
			return
		}
		if defense.LooksLikeInjection(s.DeviceID) {
			lvl := def.Record(defense.SQLInjection)
			writeJSON(w, http.StatusBadRequest, map[string]any{"status": "rejected", "reason": "injection", "defense": lvl})
			return
		}
		if len(key) > 0 && !mesh.VerifyBlock(key, s.DeviceID, s.TotalV, s.Sig) {
			lvl := def.Record(defense.BadSignature)
			writeJSON(w, http.StatusUnauthorized, map[string]any{"status": "rejected", "reason": "bad signature", "defense": lvl})
			return
		}
		if !checkWork(vault, s.DeviceID, s.TotalV, s.Work, key, def, w) {
			return
		}
		name, err := StoreBlock(vault, s.DeviceID, s.TotalV, s.Work)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"status": "error", "reason": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "synced", "file": name})
	})

	return mux
}
