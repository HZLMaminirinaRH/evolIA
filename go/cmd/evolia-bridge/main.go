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

	mux := bridge.NewServer(device, vault, key, defense.New(64))
	if err := http.ListenAndServe(addr, mux); err != nil {
		logf("server error: " + err.Error())
		os.Exit(1)
	}
}

func newLogger(path string) func(string) {
	return func(msg string) {
		fmt.Println("[evolia-bridge] " + msg)
		line, _ := json.Marshal(map[string]any{
			"timestamp": time.Now().UTC().Format(time.RFC3339),
			"message":   msg,
		})
		if f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600); err == nil {
			f.Write(append(line, '\n'))
			f.Close()
		}
	}
}
