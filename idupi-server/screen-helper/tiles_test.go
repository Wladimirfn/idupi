package main

import (
	"image"
	"testing"
)

// Dirty-tile selection is a PURE function over pixel bytes (brief §7.4):
// it must be decided here, without any screen, so the capture path only
// plugs real pixels in.

const tW, tH, tile = 128, 128, 64 // 2x2 grid

func solid(w, h int, val byte) []byte {
	pix := make([]byte, w*h*4)
	for i := range pix {
		pix[i] = val
	}
	return pix
}

func TestTileCountCoversPartialEdgeTiles(t *testing.T) {
	if got := TileCount(128, 128, 64); got != 4 {
		t.Fatalf("exact grid: got %d, want 4", got)
	}
	// 100px wide with 64px tiles needs a clipped second column; 65px tall
	// needs a clipped second row: 2 cols x 2 rows.
	if got := TileCount(100, 65, 64); got != 4 {
		t.Fatalf("ragged grid: got %d, want 4", got)
	}
}

func TestTileRectClipsToFrameBounds(t *testing.T) {
	r := TileRect(3, 100, 65, 64)
	want := image.Rect(64, 64, 100, 65)
	if !r.Eq(want) {
		t.Fatalf("last ragged tile: got %v, want %v", r, want)
	}
}

func TestDirtyTilesIdenticalFramesSendNothing(t *testing.T) {
	cur := solid(tW, tH, 7)
	dirty := DirtyTiles(cur, cur, tW, tH, tile)
	if len(dirty) != 0 {
		t.Fatalf("identical frames must send zero tiles, got %v", dirty)
	}
}

func TestDirtyTilesEmptyPreviousMeansFullKeyframe(t *testing.T) {
	cur := solid(tW, tH, 7)
	dirty := DirtyTiles(cur, nil, tW, tH, tile)
	if len(dirty) != 4 {
		t.Fatalf("no previous frame: every tile is dirty, got %v", dirty)
	}
}

func TestDirtyTilesOneChangedPixelDirtiesOnlyItsTile(t *testing.T) {
	prev := solid(tW, tH, 7)
	cur := solid(tW, tH, 7)
	// Pixel (70, 10) lives in tile index 1 (col 1, row 0).
	i := (10*tW + 70) * 4
	cur[i] ^= 0xFF
	dirty := DirtyTiles(cur, prev, tW, tH, tile)
	if len(dirty) != 1 || dirty[0] != 1 {
		t.Fatalf("one pixel must dirty exactly tile 1, got %v", dirty)
	}
}

func TestDirtyTilesMismatchedLengthsAreAnError(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatalf("mismatched pixel buffers must panic loudly, not silently diff garbage")
		}
	}()
	DirtyTiles(solid(tW, tH, 1), solid(tW, tH+1, 1), tW, tH, tile)
}
