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
	"strings"
	"sync"
	"time"

	"evolia/mesh"
	"evolia/paths"
)

const (
	cycle     = 2 * time.Second
	blockAddr = ":5555"
)

func main() {
	vault := paths.MeshVault()
	if err := os.MkdirAll(vault, 0o700); err != nil {
		fmt.Fprintln(os.Stderr, "mesh-sync: cannot create vault:", err)
		os.Exit(1)
	}

	logf := newLogger(paths.MeshSyncLog())
	logf(fmt.Sprintf("start vault=%s cycle=%s listen=%s", vault, cycle, blockAddr))

	seen := map[string]bool{}
	var mu sync.Mutex

	// Receive blocks propagated by peers and store them in the vault.
	go listenBlocks(vault, seen, &mu, logf)

	for {
		// Peers come from EVOLIA_PEERS plus whatever evolia-net has discovered;
		// reloaded each cycle so newly found peers are picked up.
		peers := dedupe(append(parsePeers(os.Getenv("EVOLIA_PEERS")), mesh.LoadPeers()...))

		mu.Lock()
		blocks, err := mesh.NewBlocks(vault, seen)
		mu.Unlock()
		if err != nil {
			logf("scan error: " + err.Error())
		}
		for _, b := range blocks {
			propagate(b, peers, logf)
		}
		time.Sleep(cycle)
	}
}

// listenBlocks receives propagated blocks over UDP and stores them in the vault.
// Each stored block is marked seen under the same lock the propagate loop uses,
// so a received block is never forwarded again (no amplification loops).
func listenBlocks(vault string, seen map[string]bool, mu *sync.Mutex, logf func(string)) {
	addr, err := net.ResolveUDPAddr("udp", blockAddr)
	if err != nil {
		logf("block listen resolve error: " + err.Error())
		return
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		logf("block listen error: " + err.Error())
		return
	}
	defer conn.Close()

	buf := make([]byte, 4096)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		mu.Lock()
		name, storeErr := mesh.StoreIncoming(vault, buf[:n])
		if storeErr == nil {
			seen[name] = true
		}
		mu.Unlock()
		if storeErr != nil {
			logf("recv from " + src.IP.String() + " rejected: " + storeErr.Error())
		} else {
			logf("received " + name + " from " + src.IP.String())
		}
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

func dedupe(in []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range in {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
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
