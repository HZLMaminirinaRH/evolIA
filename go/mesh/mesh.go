// Package mesh implements detection and propagation of evolIA mesh blocks.
//
// Blocks are JSON files dropped into the mesh vault under EVOLIA_HOME. This
// package mirrors the shared layout so the Go mesh-sync service reads the same
// vault the rest of the system writes to, and the same peer list evolia-net
// discovers.
package mesh

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"evolia/defense"
	"evolia/paths"
	"evolia/pow"
)

// Block is the shape read from a vault file and carried over the wire. Sig,
// Params and Work are only present on propagated (wire) blocks. Work — the
// cognitive proof-of-work backing the value — is persisted in the vault too so
// a relayed block carries its proof to the next hop.
type Block struct {
	Name   string             `json:"-"`
	Device string             `json:"device_id"`
	VValue float64            `json:"v_value"`
	Sig    string             `json:"sig,omitempty"`
	Params map[string]float64 `json:"cognitive_params,omitempty"`
	Work   *pow.WorkProof     `json:"work,omitempty"`
}

// Classified intake errors so the defense layer can score the right attack.
var (
	ErrMalformed    = errors.New("malformed block")
	ErrInjection    = errors.New("injection-like device id")
	ErrBadSignature = errors.New("bad block signature")
	// Re-exported from pow so callers switch on a single package's errors:
	// a value claim not backed by sound work, and a non-advancing (stale) claim.
	ErrForgedWork = pow.ErrForgedWork
	ErrStale      = pow.ErrStale
)

// SignBlock returns the HMAC-SHA256 (hex) of a block's value claim under the
// shared fleet key (EVOLIA_MESH_KEY). The signed message is canonical so it
// matches across the JSON round-trip.
func SignBlock(key []byte, device string, vValue float64) string {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(device + "|" + strconv.FormatFloat(vValue, 'f', -1, 64)))
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyBlock checks a block signature in constant time.
func VerifyBlock(key []byte, device string, vValue float64, sig string) bool {
	want, err := hex.DecodeString(sig)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(device + "|" + strconv.FormatFloat(vValue, 'f', -1, 64)))
	return hmac.Equal(want, mac.Sum(nil))
}

// NewBlocks returns the vault's *.json files not already in seen, marking each
// returned file as seen. Files that fail to parse are skipped (but still marked
// seen so a broken file is not retried forever).
func NewBlocks(vault string, seen map[string]bool) ([]Block, error) {
	matches, err := filepath.Glob(filepath.Join(vault, "*.json"))
	if err != nil {
		return nil, err
	}
	sort.Strings(matches)

	var out []Block
	for _, path := range matches {
		name := filepath.Base(path)
		if seen[name] {
			continue
		}
		seen[name] = true

		b, err := readBlock(path)
		if err != nil {
			continue
		}
		b.Name = name
		out = append(out, b)
	}
	return out, nil
}

// TotalV sums v_value across all blocks currently in the vault.
func TotalV(vault string) float64 {
	matches, err := filepath.Glob(filepath.Join(vault, "*.json"))
	if err != nil {
		return 0
	}
	var total float64
	for _, path := range matches {
		if b, err := readBlock(path); err == nil {
			total += b.VValue
		}
	}
	return total
}

// StoreIncoming validates and stores a block datagram propagated by a peer's
// mesh-sync, so TotalV and the dashboard pick it up. The file is keyed by device
// id (recv_<device>.json), so re-sends from the same peer overwrite rather than
// accumulate. With a non-nil key the block's HMAC signature must verify.
//
// Returns the file name written (which the caller should mark "seen" to avoid
// re-propagating a received block) and any cognitive params carried, or a
// classified error (ErrMalformed / ErrInjection / ErrBadSignature) the defense
// layer scores.
func StoreIncoming(vault string, data []byte, key []byte) (string, map[string]float64, error) {
	var b Block
	if err := json.Unmarshal(data, &b); err != nil {
		return "", nil, ErrMalformed
	}
	if b.Device == "" {
		return "", nil, ErrMalformed
	}
	if defense.LooksLikeInjection(b.Device) {
		return "", nil, ErrInjection
	}
	if len(key) > 0 {
		if !VerifyBlock(key, b.Device, b.VValue, b.Sig) {
			return "", nil, ErrBadSignature
		}
		// A keyed fleet speaks proof-of-work: every value claim must prove it.
		if b.Work == nil {
			return "", nil, ErrForgedWork
		}
	}
	if b.Work != nil {
		if err := pow.ValidateBlock(StoredV(vault, b.Device), b.VValue, *b.Work); err != nil {
			return "", nil, err // ErrStale (skip) or ErrForgedWork (attack)
		}
	}
	name, err := StorePeerBlock(vault, b.Device, b.VValue, b.Work)
	if err != nil {
		return "", nil, err
	}
	return name, b.Params, nil
}

// StoredV returns the value we currently hold for a device (0 if none), so the
// proof-of-work validator can check the increment and enforce monotonicity.
func StoredV(vault, device string) float64 {
	name := fmt.Sprintf("recv_%s.json", sanitizeDevice(device))
	if b, err := readBlock(filepath.Join(vault, name)); err == nil {
		return b.VValue
	}
	return 0
}

// StorePeerBlock writes a peer's value into the vault as recv_<device>.json,
// keyed by device id so a re-send from the same peer overwrites rather than
// accumulates — TotalV never double-counts a peer, whether the block arrived
// over UDP (mesh-sync) or HTTP (the bridge). Returns the file name written.
func StorePeerBlock(vault, device string, vValue float64, work *pow.WorkProof) (string, error) {
	if err := os.MkdirAll(vault, 0o700); err != nil {
		return "", err
	}
	name := fmt.Sprintf("recv_%s.json", sanitizeDevice(device))
	payload := map[string]any{
		"device_id": device,
		"v_value":   vValue,
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	}
	if work != nil {
		payload["work"] = work // persist the proof so a relay carries it onward
	}
	data, _ := json.Marshal(payload)
	if err := paths.WriteFileAtomic(filepath.Join(vault, name), data, 0o600); err != nil {
		return "", err
	}
	return name, nil
}

func sanitizeDevice(s string) string {
	r := strings.NewReplacer("/", "_", "\\", "_", ".", "_", " ", "_", ":", "_")
	if out := r.Replace(s); out != "" {
		return out
	}
	return "peer"
}

func readBlock(path string) (Block, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return Block{}, err
	}
	var b Block
	if err := json.Unmarshal(data, &b); err != nil {
		return Block{}, err
	}
	return b, nil
}

// LoadPeers returns peer host addresses from the peers file written by
// evolia-net. Returns nil if the file is missing or unreadable.
func LoadPeers() []string {
	data, err := os.ReadFile(paths.PeersFile())
	if err != nil {
		return nil
	}
	var peers []struct {
		Addr string `json:"addr"`
	}
	if json.Unmarshal(data, &peers) != nil {
		return nil
	}
	var out []string
	for _, p := range peers {
		if p.Addr != "" {
			out = append(out, p.Addr)
		}
	}
	return out
}
