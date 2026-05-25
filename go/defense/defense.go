// Package defense is the Go mirror of evolia-security's evolutive defense: the
// more hostile input a service absorbs, the harder it defends.
//
// AdaptiveDefense is a bounded, concurrency-safe ring of recent attacks — the
// "mémoire tampon en son sein" — whose level is the accumulated severity it
// holds and decays as quiet ticks pass. LooksLikeInjection flags SQLi-like
// control inputs. Strictly reactive: detect, reject, harden, record — never
// retaliate.
package defense

import (
	"strings"
	"sync"
)

// AttackKind classifies absorbed hostile input; severity feeds the level.
type AttackKind int

const (
	SQLInjection AttackKind = iota
	BadSignature
	Unauthorized
	Malformed
)

func (k AttackKind) severity() float64 {
	switch k {
	case SQLInjection:
		return 1.0
	case BadSignature:
		return 0.8
	case Unauthorized:
		return 0.6
	case Malformed:
		return 0.3
	default:
		return 0.0
	}
}

// AdaptiveDefense is a bounded ring of recent attack severities.
type AdaptiveDefense struct {
	mu       sync.Mutex
	capacity int
	buffer   []float64
}

func New(capacity int) *AdaptiveDefense {
	if capacity < 1 {
		capacity = 1
	}
	return &AdaptiveDefense{capacity: capacity}
}

// Record absorbs an attack, evicting the oldest when full, and returns the new
// defense level.
func (d *AdaptiveDefense) Record(kind AttackKind) float64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.buffer) == d.capacity {
		d.buffer = d.buffer[1:]
	}
	d.buffer = append(d.buffer, kind.severity())
	return d.sum()
}

// Level is the accumulated severity of buffered attacks: the more evolIA has
// absorbed, the higher (and the stricter callers should be).
func (d *AdaptiveDefense) Level() float64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.sum()
}

// Decay forgets the oldest buffered attack — call on a quiet tick so the
// defense relaxes when the pressure stops.
func (d *AdaptiveDefense) Decay() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.buffer) > 0 {
		d.buffer = d.buffer[1:]
	}
}

// Len is the number of buffered attacks.
func (d *AdaptiveDefense) Len() int {
	d.mu.Lock()
	defer d.mu.Unlock()
	return len(d.buffer)
}

func (d *AdaptiveDefense) sum() float64 {
	var total float64
	for _, s := range d.buffer {
		total += s
	}
	return total
}

// LooksLikeInjection flags SQL-injection-like payloads on control inputs
// (device ids, peer fields). A positive result means: reject and Record.
func LooksLikeInjection(input string) bool {
	if strings.ContainsRune(input, 0) {
		return true
	}
	lower := strings.ToLower(input)
	needles := []string{
		"' or ", "\" or ", " or 1=1", "'='",
		"--", "/*", "*/", "; drop ", " union select", "xp_cmdshell", "';",
	}
	for _, n := range needles {
		if strings.Contains(lower, n) {
			return true
		}
	}
	return false
}
