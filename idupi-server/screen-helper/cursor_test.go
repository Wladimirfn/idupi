package main

import "testing"

// BitBlt and StretchBlt copy the desktop surface, and the mouse pointer is not
// part of it -- Windows composites the cursor separately. A capture therefore
// arrives without one, which for a remote screen means the user cannot see
// where they are pointing. It has to be drawn in afterwards.
//
// Placing it is the part worth testing without a screen: the cursor position
// arrives in virtual-desktop coordinates, the monitor may start at a negative
// origin, and the capture is scaled down to the viewer's viewport.

func TestCursorLandsOnTheScaledCapture(t *testing.T) {
	// 1920x1080 monitor captured at half size: a cursor at its centre must
	// land at the centre of the capture.
	m := Monitor{X: 0, Y: 0, Width: 1920, Height: 1080}

	x, y, ok := cursorDrawPoint(m, 960, 540, 0, 0, 960, 540)

	if !ok {
		t.Fatal("el cursor está sobre el monitor, debería dibujarse")
	}
	if x != 480 || y != 270 {
		t.Fatalf("esperaba (480,270), obtuve (%d,%d)", x, y)
	}
}

func TestCursorOnAMonitorWithNegativeOrigin(t *testing.T) {
	// The user's left monitor starts at x=-1920. Any maths assuming a (0,0)
	// origin puts the cursor off-screen on exactly half of their desktop.
	m := Monitor{X: -1920, Y: 0, Width: 1920, Height: 1080}

	x, y, ok := cursorDrawPoint(m, -960, 540, 0, 0, 1920, 1080)

	if !ok {
		t.Fatal("el cursor está sobre el monitor izquierdo, debería dibujarse")
	}
	if x != 960 || y != 540 {
		t.Fatalf("esperaba (960,540), obtuve (%d,%d)", x, y)
	}
}

func TestCursorOnAnotherMonitorIsNotDrawn(t *testing.T) {
	// Two monitors, one stream: the pointer sitting on the other one must not
	// be smeared onto the edge of this capture.
	m := Monitor{X: 0, Y: 0, Width: 1920, Height: 1080}

	if _, _, ok := cursorDrawPoint(m, -500, 400, 0, 0, 960, 540); ok {
		t.Fatal("el cursor está en el otro monitor, no debe dibujarse")
	}
	if _, _, ok := cursorDrawPoint(m, 300, 2000, 0, 0, 960, 540); ok {
		t.Fatal("el cursor está debajo del monitor, no debe dibujarse")
	}
}

func TestHotspotIsNotScaledWithThePosition(t *testing.T) {
	// The icon is drawn at its native size, so its hotspot offset is in icon
	// pixels. Scaling it too would drift the tip away from the real point.
	m := Monitor{X: 0, Y: 0, Width: 1920, Height: 1080}

	x, y, ok := cursorDrawPoint(m, 960, 540, 10, 6, 960, 540)

	if !ok {
		t.Fatal("debería dibujarse")
	}
	if x != 470 || y != 264 {
		t.Fatalf("esperaba (470,264) = (480,270) menos el hotspot, obtuve (%d,%d)", x, y)
	}
}

func TestADegenerateMonitorIsRefusedInsteadOfDividingByZero(t *testing.T) {
	m := Monitor{X: 0, Y: 0, Width: 0, Height: 0}

	if _, _, ok := cursorDrawPoint(m, 0, 0, 0, 0, 100, 100); ok {
		t.Fatal("un monitor sin tamaño no puede ubicar nada")
	}
}
