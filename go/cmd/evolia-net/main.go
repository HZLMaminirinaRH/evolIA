// Command evolia-net discovers LAN peers and records them for mesh-sync.
//
// It listens for announce datagrams on UDP :5557 and periodically broadcasts
// its own announce to 255.255.255.255:5557. Each discovered peer is written to
// evolia_peers.json (host only); mesh-sync reads that file and dials peers on
// the block port (5555). Standard library only.
//
// Port allocation (no collision with mesh-sync):
//
//	:5555 — mesh-sync block intake (value sync)
//	:5556 — mesh-sync chat intake  (opaque E2E envelopes)
//	:5557 — evolia-net discovery   (LAN announces)
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	"evolia/netdisc"
	"evolia/paths"
)

const (
	discoveryAddr = ":5557"
	broadcastAddr = "255.255.255.255:5557"
	announceEvery = 30 * time.Second
)

func main() {
	self := paths.DeviceID()
	logf := newLogger(paths.NetworkLog())
	reg := netdisc.NewRegistry()
	logf("start device=" + self + " listen=" + discoveryAddr)

	go listen(self, reg, logf)
	announce(self, logf)
}

func listen(self string, reg *netdisc.Registry, logf func(string)) {
	addr, err := net.ResolveUDPAddr("udp", discoveryAddr)
	if err != nil {
		logf("resolve error: " + err.Error())
		return
	}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		logf("listen error: " + err.Error())
		return
	}
	defer conn.Close()

	buf := make([]byte, 2048)
	for {
		n, src, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		id, ok := netdisc.ParseAnnounce(buf[:n])
		if !ok || id == self {
			continue
		}
		if reg.Add(id, src.IP.String()) {
			logf("peer discovered: " + id + " @ " + src.IP.String())
		}
		if err := reg.Save(); err != nil {
			logf("save error: " + err.Error())
		}
	}
}

func announce(self string, logf func(string)) {
	payload, _ := json.Marshal(netdisc.Announce{DeviceID: self})
	dst, err := net.ResolveUDPAddr("udp", broadcastAddr)
	if err != nil {
		logf("broadcast resolve error: " + err.Error())
		return
	}
	for {
		if conn, err := net.DialUDP("udp", nil, dst); err == nil {
			_, _ = conn.Write(payload)
			conn.Close()
		} else {
			logf("broadcast error: " + err.Error())
		}
		time.Sleep(announceEvery)
	}
}

func newLogger(path string) func(string) {
	// Open the log once and keep it: one write per line instead of an
	// open/write/close syscall trio per message (battery + flash on mobile).
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	var mu sync.Mutex
	return func(msg string) {
		fmt.Println("[evolia-net] " + msg)
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
