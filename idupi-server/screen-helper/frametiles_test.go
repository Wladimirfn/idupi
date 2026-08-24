package main

import (
	"bytes"
	"image"
	"image/jpeg"
	"testing"
)

// The tile frame builder turns (current pixels, previous pixels) into either
// a legacy full JPEG or a set of per-tile JPEGs -- all decided WITHOUT a
// screen, so the GDI capture path only plugs real pixels in (brief §7.4).

func TestShouldKeyframeFollowsTheRatioRule(t *testing.T) {
	cases := []struct {
		dirty, total int
		want         bool
	}{
		{9, 9, true},  // everything changed
		{6, 9, true},  // >60% changed: one JPEG beats seven tile JPEGs
		{5, 9, false}, // 55%: tiles win
		{1, 400, false},
		{0, 400, false},
	}
	for _, c := range cases {
		if got := ShouldKeyframe(c.dirty, c.total); got != c.want {
			t.Fatalf("ShouldKeyframe(%d,%d) = %v, want %v", c.dirty, c.total, got, c.want)
		}
	}
}

func synthFrame(w, h int, val byte) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := range img.Pix {
		img.Pix[i] = val
	}
	return img
}

func TestBuildTileFrameWithoutPreviousIsALegacyKeyframe(t *testing.T) {
	meta, body, err := buildTileFrame(7, synthFrame(128, 128, 9), nil, 64, 55)
	if err != nil {
		t.Fatalf("keyframe: %v", err)
	}
	if meta.Type != "" {
		t.Fatalf("keyframes must keep the LEGACY meta shape (no type field) so old APKs keep working, got %q", meta.Type)
	}
	if meta.ID != 7 || meta.W != 128 || meta.H != 128 {
		t.Fatalf("legacy meta fields wrong: %+v", meta)
	}
	img, err := jpeg.Decode(bytes.NewReader(body))
	if err != nil {
		t.Fatalf("body must be one decodable JPEG: %v", err)
	}
	if b := img.Bounds(); b.Dx() != 128 || b.Dy() != 128 {
		t.Fatalf("keyframe bounds: %v", b)
	}
}

func TestBuildTileFrameUnchangedScreenSendsAnEmptyTileSet(t *testing.T) {
	img := synthFrame(128, 128, 9)
	meta, body, err := buildTileFrame(8, img, img.Pix, 64, 55)
	if err != nil {
		t.Fatalf("unchanged: %v", err)
	}
	if meta.Type != "tiles" || len(meta.Tiles) != 0 {
		t.Fatalf("unchanged screen must be an empty tile set, got type=%q tiles=%d", meta.Type, len(meta.Tiles))
	}
	if len(body) != 0 {
		t.Fatalf("empty tile set must carry no payload")
	}
	if meta.Tw != 64 || meta.Th != 64 {
		t.Fatalf("tile geometry missing: %+v", meta)
	}
}

func TestBuildTileFrameSendsOnlyTheDirtyTiles(t *testing.T) {
	prev := synthFrame(128, 128, 9)
	cur := synthFrame(128, 128, 9)
	// Change two pixels inside DIFFERENT tiles: (10,10) -> tile 0, (70,70) -> tile 3.
	cur.Pix[(10*128+10)*4] ^= 0xFF
	cur.Pix[(70*128+70)*4] ^= 0xFF

	meta, body, err := buildTileFrame(9, cur, prev.Pix, 64, 55)
	if err != nil {
		t.Fatalf("tiles: %v", err)
	}
	if meta.Type != "tiles" || len(meta.Tiles) != 2 {
		t.Fatalf("expected exactly 2 dirty tiles, got %+v", meta.Tiles)
	}
	if meta.Tiles[0].I != 0 || meta.Tiles[1].I != 3 {
		t.Fatalf("dirty indices wrong: %+v", meta.Tiles)
	}

	// Each declared slice decodes to its tile's rectangle.
	offset := 0
	for _, ref := range meta.Tiles {
		chunk := body[offset : offset+ref.Len]
		offset += ref.Len
		img, err := jpeg.Decode(bytes.NewReader(chunk))
		if err != nil {
			t.Fatalf("tile %d does not decode: %v", ref.I, err)
		}
		want := TileRect(ref.I, 128, 128, 64)
		if b := img.Bounds(); b.Dx() != want.Dx() || b.Dy() != want.Dy() {
			t.Fatalf("tile %d bounds %v, want %v", ref.I, b, want)
		}
	}
	if offset != len(body) {
		t.Fatalf("payload has %d trailing bytes", len(body)-offset)
	}
}

func TestFrameCacheRoundTripsAndInvalidates(t *testing.T) {
	var c frameCache
	if pix := c.lookup("0:128:128:55"); pix != nil {
		t.Fatalf("empty cache must miss")
	}
	c.store("k1", []byte{1, 2, 3})
	c.store("k1", []byte{9, 9}) // overwrite, never grow unbounded
	if got := c.lookup("k1"); len(got) != 2 || got[0] != 9 {
		t.Fatalf("cache lookup broken: %v", got)
	}
	if c.lookup("k2") != nil {
		t.Fatalf("different key must miss")
	}
}
