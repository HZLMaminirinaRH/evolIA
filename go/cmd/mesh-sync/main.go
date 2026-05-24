// Command mesh-sync watches the evolIA mesh vault and propagates new blocks.
//
// Every cycle it detects vault files it has not seen and either forwards them
// to configured peers over UDP (EVOLIA_PEERS, comma-separated host[:port],
// default port 5555) or, with no peers, logs them locally. Events are written
// to evolia_mesh_sync.log under EVOLIA_HOME. Standard library only.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"evolia/mesh"
)

const cycle = 2 * time.Second

func main() {
	vault := mesh.VaultDir()
	if err := os.MkdirAll(vault, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "mesh-sync: cannot create vault:", err)
		os.Exit(1)
	}

	peers := parsePeers(os.Getenv("EVOLIA_PEERS"))
	logf := newLogger(filepath.Join(mesh.Home(), "evolia_mesh_sync.log"))
	logf(fmt.Sprintf("start vault=%s peers=%d cycle=%s", vault, len(peers), cycle))

	seen := map[string]bool{}
	for {
		blocks, err := mesh.NewBlocks(vault, seen)
		if err != nil {
			logf("scan error: " + err.Error())
		}
		for _, b := range blocks {
			propagate(b, peers, logf)
		}
		time.Sleep(cycle)
	}
}

func parsePeers(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func propagate(b mesh.Block, peers []string, logf func(string)) {
	if len(peers) == 0 {
		logf(fmt.Sprintf("local block %s (device=%s v=%.4f)", b.Name, b.Device, b.VValue))
		return
	}
	payload, _ := json.Marshal(b)
	for _, peer := range peers {
		addr := peer
		if !strings.Contains(addr, ":") {
			addr += ":5555"
		}
		conn, err := net.DialTimeout("udp", addr, time.Second)
		if err != nil {
			logf("dial " + addr + " failed: " + err.Error())
			continue
		}
		_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
		if _, err := conn.Write(payload); err != nil {
			logf("send " + addr + " failed: " + err.Error())
		} else {
			logf("sent " + b.Name + " -> " + addr)
		}
		conn.Close()
	}
}

func newLogger(path string) func(string) {
	return func(msg string) {
		fmt.Println("[mesh-sync] " + msg)
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
