// Command evolia-net discovers LAN peers and records them for mesh-sync.
//
// It listens for announce datagrams on UDP :5556 and periodically broadcasts
// its own announce to 255.255.255.255:5556. Each discovered peer is written to
// evolia_peers.json (host only); mesh-sync reads that file and dials peers on
// the block port (5555). Standard library only.
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"time"

	"evolia/netdisc"
	"evolia/paths"
)

const (
	discoveryAddr = ":5556"
	broadcastAddr = "255.255.255.255:5556"
	announceEvery = 30 * time.Second
)

func main() {
	self := deviceID()
	logf := newLogger(paths.NetworkLog())
	reg := netdisc.NewRegistry()
	logf("start device=" + self + " listen=" + discoveryAddr)

	go listen(self, reg, logf)
	announce(self, logf)
}

func deviceID() string {
	if v := os.Getenv("EVOLIA_DEVICE_ID"); v != "" {
		return v
	}
	if h, err := os.Hostname(); err == nil && h != "" {
		return h
	}
	return "evolia-node"
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
	return func(msg string) {
		fmt.Println("[evolia-net] " + msg)
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
