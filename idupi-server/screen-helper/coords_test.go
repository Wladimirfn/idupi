package main

import "testing"

func TestVirtualBoundsUnionsAllMonitors(t *testing.T) {
	// Left monitor starts at x=-1920 on the target machine.
	all := []Bounds{
		{X: -1920, Y: 0, W: 1920, H: 1080},
		{X: 0, Y: 0, W: 1920, H: 1080},
	}
	got := VirtualBounds(all)
	want := Bounds{X: -1920, Y: 0, W: 3840, H: 1080}
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func TestAbsolutePointerSingleMonitorAtOrigin(t *testing.T) {
	m := Bounds{X: 0, Y: 0, W: 1920, H: 1080}
	v := m

	x, y := AbsolutePointer(m, v, 0, 0)
	if x != 0 || y != 0 {
		t.Fatalf("origin: got (%d,%d), want (0,0)", x, y)
	}
	x, y = AbsolutePointer(m, v, 1, 1)
	if x != 65535 || y != 65535 {
		t.Fatalf("far corner: got (%d,%d), want (65535,65535)", x, y)
	}
}

func TestAbsolutePointerNegativeOriginMonitor(t *testing.T) {
	left := Bounds{X: -1920, Y: 0, W: 1920, H: 1080}
	right := Bounds{X: 0, Y: 0, W: 1920, H: 1080}
	v := Bounds{X: -1920, Y: 0, W: 3840, H: 1080}

	// nx=0 on the left monitor is the virtual desktop's left edge.
	x, _ := AbsolutePointer(left, v, 0, 0.5)
	if x != 0 {
		t.Fatalf("left edge of left monitor: got %d, want 0", x)
	}
	// nx=1 on the left monitor is the seam between monitors, not the
	// desktop's right edge. It lands on the monitor's last pixel (x=-1),
	// which maps slightly below the exact midpoint: 1919 * 65535 / 3839.
	x, _ = AbsolutePointer(left, v, 1, 0.5)
	if x != 32759 {
		t.Fatalf("seam: got %d, want 32759 (pixel-grid midpoint)", x)
	}
	// nx=1 on the right monitor is the virtual desktop's right edge.
	x, _ = AbsolutePointer(right, v, 1, 0.5)
	if x != 65535 {
		t.Fatalf("right edge: got %d, want 65535", x)
	}
}

func TestAbsolutePointerClampsOutOfRange(t *testing.T) {
	m := Bounds{X: 0, Y: 0, W: 1920, H: 1080}

	x, y := AbsolutePointer(m, m, -0.2, 1.5)
	if x != 0 || y != 65535 {
		t.Fatalf("clamped: got (%d,%d), want (0,65535)", x, y)
	}
}

func TestAbsolutePointerRejectsDegenerateBounds(t *testing.T) {
	m := Bounds{X: 0, Y: 0, W: 0, H: 10}
	if _, _, ok := pointerChecked(m, m, 0.5, 0.5); ok {
		t.Fatal("expected failure for zero-width monitor")
	}
}
