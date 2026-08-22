package main

import "testing"

func TestFitWithinKeepsRequestedSizeWhenSmaller(t *testing.T) {
	w, h := FitWithin(1920, 1080, 800, 450)
	if w != 800 || h != 450 {
		t.Fatalf("got (%d,%d), want (800,450)", w, h)
	}
}

func TestFitWithinScalesDownOversizedRequest(t *testing.T) {
	// Requesting native scale or more must never exceed the native size.
	w, h := FitWithin(1920, 1080, 2400, 1350)
	if w != 1920 || h != 1080 {
		t.Fatalf("got (%d,%d), want (1920,1080)", w, h)
	}
}

func TestFitWithinPreservesRequestAspectRatio(t *testing.T) {
	// Requested aspect wins over native aspect: server decides framing,
	// helper never crops.
	w, h := FitWithin(1920, 1080, 4000, 500)
	if w != 1920 || h != 240 {
		t.Fatalf("got (%d,%d), want (1920,240)", w, h)
	}
}

func TestFitWithinRejectsDegenerateInputs(t *testing.T) {
	if w, h := FitWithin(0, 1080, 100, 100); w != 0 || h != 0 {
		t.Fatalf("zero-width native: got (%d,%d), want (0,0)", w, h)
	}
	if w, h := FitWithin(1920, 1080, 0, 100); w != 0 || h != 0 {
		t.Fatalf("zero-width request: got (%d,%d), want (0,0)", w, h)
	}
}
