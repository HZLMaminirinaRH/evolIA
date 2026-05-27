// Command evolia-bridge serves the peer block-exchange HTTP API.
//
// Peers POST their V blocks to /block; blocks land in the shared mesh vault and
// incoming cognitive params are fused locally. Endpoints: /block, /sync,
// /mesh/total_v, /health. Standard library only. Listen address: :8080
// (override with EVOLIA_BRIDGE_ADDR).
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"

	"evolia/bridge"
	"evolia/defense"
	"evolia/paths"
)

func main() {
	addr := os.Getenv("EVOLIA_BRIDGE_ADDR")
	if addr == "" {
		addr = ":8080"
	}
	device := paths.DeviceID()
	vault := paths.MeshVault()
	if err := os.MkdirAll(vault, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "evolia-bridge: cannot create vault:", err)
		os.Exit(1)
	}

	key := []byte(os.Getenv("EVOLIA_MESH_KEY"))
	logf := newLogger(paths.BridgeLog())
	logf(fmt.Sprintf("start device=%s addr=%s vault=%s signed=%t", device, addr, vault, len(key) > 0))

	def := defense.New(64)
	// Relax the adaptive defense one notch per quiet tick so it breathes back
	// down once an attack burst stops, instead of only ever climbing. The tempo
	// matches mesh-sync (EVOLIA_MESH_CYCLE_SECONDS, default 5s).
	go func() {
		for range time.Tick(decayInterval()) {
			def.Decay()
		}
	}()

	mux := bridge.NewServer(device, vault, key, def)
	if err := http.ListenAndServe(addr, mux); err != nil {
		logf("server error: " + err.Error())
		os.Exit(1)
	}
}

// decayInterval is the defense relaxation tempo, shared with mesh-sync via
// EVOLIA_MESH_CYCLE_SECONDS (default 5s).
func decayInterval() time.Duration {
	if s := os.Getenv("EVOLIA_MESH_CYCLE_SECONDS"); s != "" {
		if v, err := strconv.Atoi(s); err == nil && v > 0 {
			return time.Duration(v) * time.Second
		}
	}
	return 5 * time.Second
}

func newLogger(path string) func(string) {
	// Open the log once and keep it: one write per line instead of an
	// open/write/close syscall trio per message (battery + flash on mobile).
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	var mu sync.Mutex
	return func(msg string) {
		fmt.Println("[evolia-bridge] " + msg)
		if f == nil {
			return
		}
		line, _ := json.Marshal(map[string]any{
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"message":   msg,
		})
		mu.Lock()
		f.Write(append(line, '\n'))
		mu.Unlock()
	}
}
