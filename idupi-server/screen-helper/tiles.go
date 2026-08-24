package main

import (
	"image"
)

// Dirty-tile selection (brief §4.3): a desktop is mostly static, so only the
// tiles that changed since the previous frame travel the wire. Everything
// here is a PURE function over pixel bytes -- testable without a screen.

// TileCount returns cols*rows for a frame of w x h with square tiles; edge
// tiles may be clipped.
func TileCount(w, h, tile int) int {
	cols := (w + tile - 1) / tile
	rows := (h + tile - 1) / tile
	return cols * rows
}

// TileRect maps a row-major tile index to its pixel rectangle, clipped to
// the frame bounds so ragged edges stay inside the frame.
func TileRect(index, w, h, tile int) image.Rectangle {
	cols := (w + tile - 1) / tile
	x0 := (index % cols) * tile
	y0 := (index / cols) * tile
	x1 := minInt(x0+tile, w)
	y1 := minInt(y0+tile, h)
	return image.Rect(x0, y0, x1, y1)
}

// DirtyTiles compares two RGBA frames and returns the row-major indices of
// tiles whose pixels differ. A nil previous frame means "nothing cached yet"
// -- every tile is dirty, i.e. a full keyframe. Mismatched buffer lengths are
// a programming bug and panic loudly rather than diffing garbage.
func DirtyTiles(cur, prev []byte, w, h, tile int) []int {
	if prev == nil {
		all := make([]int, TileCount(w, h, tile))
		for i := range all {
			all[i] = i
		}
		return all
	}
	if len(cur) != len(prev) {
		panic("dirty-tile diff needs equal-length pixel buffers")
	}
	var dirty []int
	count := TileCount(w, h, tile)
	for i := 0; i < count; i++ {
		r := TileRect(i, w, h, tile)
		if regionDiffers(cur, prev, w, r) {
			dirty = append(dirty, i)
		}
	}
	return dirty
}

// regionDiffers compares one rectangle's RGBA bytes between two frames.
func regionDiffers(cur, prev []byte, stride int, r image.Rectangle) bool {
	for y := r.Min.Y; y < r.Max.Y; y++ {
		row := y*stride*4 + r.Min.X*4
		a, b := cur[row:row+(r.Dx())*4], prev[row:row+(r.Dx())*4]
		for i := range a {
			if a[i] != b[i] {
				return true
			}
		}
	}
	return false
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
