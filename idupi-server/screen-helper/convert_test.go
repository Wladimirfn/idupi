package main

import "testing"

func TestBGRAToRGBASwapsChannelsAndForcesOpaqueAlpha(t *testing.T) {
	// Two pixels: BGRA in, RGBA out.
	src := []byte{
		10, 20, 30, 0xFF,
		40, 50, 60, 0x00,
	}
	got := BGRAToRGBA(src)
	want := []byte{
		30, 20, 10, 0xFF,
		60, 50, 40, 0xFF,
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("byte %d: got %d, want %d", i, got[i], want[i])
		}
	}
}

func TestBGRAToRGBADoesNotAliasSource(t *testing.T) {
	src := []byte{10, 20, 30, 0xFF}
	got := BGRAToRGBA(src)
	if &got[0] == &src[0] {
		t.Fatal("result aliases source; mutating the capture buffer would corrupt reuse")
	}
}

func TestBGRAToRGBARejectsNonMultipleOfFour(t *testing.T) {
	if _, err := safeConvert([]byte{1, 2, 3}); err == nil {
		t.Fatal("expected error for buffer not a multiple of 4 bytes")
	}
}
