package defense

import "testing"

func TestDefenseGrowsAndIsBounded(t *testing.T) {
	d := New(3)
	if d.Level() != 0 {
		t.Fatalf("fresh defense must be 0, got %v", d.Level())
	}
	l1 := d.Record(Malformed)
	l2 := d.Record(SQLInjection)
	if l2 <= l1 {
		t.Fatalf("absorbing more attacks must raise the level: %v -> %v", l1, l2)
	}
	d.Record(BadSignature)
	d.Record(Unauthorized)
	if d.Len() != 3 {
		t.Fatalf("buffer must stay bounded at 3, got %d", d.Len())
	}
}

func TestDefenseRelaxesOnDecay(t *testing.T) {
	d := New(8)
	d.Record(SQLInjection)
	d.Record(SQLInjection)
	before := d.Level()
	d.Decay()
	if d.Level() >= before {
		t.Fatalf("decay must relax the defense: before=%v after=%v", before, d.Level())
	}
}

func TestInjectionDetector(t *testing.T) {
	positives := []string{
		"' OR '1'='1",
		"'; DROP TABLE peers;--",
		"a UNION SELECT password FROM users",
	}
	for _, s := range positives {
		if !LooksLikeInjection(s) {
			t.Fatalf("expected injection flagged: %q", s)
		}
	}
	negatives := []string{"phone-galaxy-a52", "owner", "evolia-node"}
	for _, s := range negatives {
		if LooksLikeInjection(s) {
			t.Fatalf("expected clean input: %q", s)
		}
	}
}
