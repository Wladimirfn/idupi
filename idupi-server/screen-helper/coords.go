package main

import "math"

// Bounds describes a monitor rectangle in virtual-desktop coordinates.
type Bounds struct {
	X, Y int32
	W, H int32
}

// VirtualBounds unions every monitor rect into the virtual-desktop bounds.
func VirtualBounds(all []Bounds) Bounds {
	if len(all) == 0 {
		return Bounds{}
	}
	minX, minY := all[0].X, all[0].Y
	maxX := all[0].X + all[0].W
	maxY := all[0].Y + all[0].H
	for _, b := range all[1:] {
		if b.X < minX {
			minX = b.X
		}
		if b.Y < minY {
			minY = b.Y
		}
		if b.X+b.W > maxX {
			maxX = b.X + b.W
		}
		if b.Y+b.H > maxY {
			maxY = b.Y + b.H
		}
	}
	return Bounds{X: minX, Y: minY, W: maxX - minX, H: maxY - minY}
}

const pointerRange = 65535 // SendInput absolute range with MOUSEEVENTF_VIRTUALDESK

// AbsolutePointer maps normalised coordinates (nx, ny in 0..1, clamped)
// against one monitor's bounds into SendInput absolute coordinates spanning
// the whole virtual desktop (MOUSEEVENTF_VIRTUALDESK range 0..65535).
// Degenerate bounds yield (0, 0). Normalising against the selected monitor
// and resolving here keeps pixel maths off the client, which does not know
// monitor origins (the target machine's left monitor starts at x=-1920).
func AbsolutePointer(monitor, virtual Bounds, nx, ny float64) (uint32, uint32) {
	x, y, _ := pointerChecked(monitor, virtual, nx, ny)
	return x, y
}

func pointerChecked(monitor, virtual Bounds, nx, ny float64) (x, y uint32, ok bool) {
	if monitor.W <= 1 || monitor.H <= 1 || virtual.W <= 0 || virtual.H <= 0 {
		return 0, 0, false
	}
	cx := clamp01(nx)
	cy := clamp01(ny)

	// Pixel inside the monitor; -1 so nx=1 lands on the last pixel, not past it.
	px := float64(monitor.X) + cx*float64(monitor.W-1)
	py := float64(monitor.Y) + cy*float64(monitor.H-1)

	ax := math.Round((px - float64(virtual.X)) * pointerRange / float64(virtual.W-1))
	ay := math.Round((py - float64(virtual.Y)) * pointerRange / float64(virtual.H-1))
	return uint32(ax), uint32(ay), true
}

func clamp01(v float64) float64 {
	return math.Max(0, math.Min(1, v))
}
