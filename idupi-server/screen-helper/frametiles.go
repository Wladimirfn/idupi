package main

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"sync"
)

// Tile frame construction (brief §4.3): the previous captured frame is kept
// in memory, only dirty tiles are JPEG-encoded and concatenated into one
// payload whose meta declares each slice. Keyframes keep the LEGACY meta
// shape -- no "type" field at all -- so an older APK keeps working during
// rollout.

// Beyond this dirty ratio one full JPEG costs fewer bytes than N tile JPEGs.
const keyframeRatio = 0.6

// ShouldKeyframe decides between a full frame and a tile set: everything
// changed, or enough changed that tiles stop being worth their per-tile
// overhead.
func ShouldKeyframe(dirtyCount, totalTiles int) bool {
	if totalTiles <= 0 || dirtyCount >= totalTiles {
		return true
	}
	return float64(dirtyCount)/float64(totalTiles) > keyframeRatio
}

// frameCache stores the previous frame's pixels keyed by capture signature
// (monitor:size:quality); any signature change forces a clean keyframe.
type frameCache struct {
	mu  sync.Mutex
	key string
	pix []byte
}

func (c *frameCache) lookup(key string) []byte {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.key != key {
		return nil
	}
	return c.pix
}

func (c *frameCache) store(key string, pix []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.key = key
	c.pix = pix
}

// buildTileFrame diffs img against prevPix and produces either a legacy
// single-JPEG frame or a "tiles" frame whose body is each dirty tile's JPEG
// concatenated in declared order. An unchanged screen yields an EMPTY tile
// set: it still carries a frame id so the ack-paced loop keeps turning at
// near-zero wire cost.
func buildTileFrame(id int, img *image.RGBA, prevPix []byte, tile int, quality int) (frameMeta, []byte, error) {
	w := img.Bounds().Dx()
	h := img.Bounds().Dy()
	dirty := DirtyTiles(img.Pix, prevPix, w, h, tile)
	total := TileCount(w, h, tile)

	if ShouldKeyframe(len(dirty), total) {
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
			return frameMeta{}, nil, fmt.Errorf("jpeg encode failed: %v", err)
		}
		// No Type field: byte-for-byte the meta shape every client knows.
		return frameMeta{ID: id, W: int32(w), H: int32(h), Bytes: buf.Len(), Quality: quality}, buf.Bytes(), nil
	}

	body := make([]byte, 0, len(dirty)*4096)
	refs := make([]tileRef, 0, len(dirty))
	for _, i := range dirty {
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, img.SubImage(TileRect(i, w, h, tile)), &jpeg.Options{Quality: quality}); err != nil {
			return frameMeta{}, nil, fmt.Errorf("tile jpeg encode failed: %v", err)
		}
		refs = append(refs, tileRef{I: i, Len: buf.Len()})
		body = append(body, buf.Bytes()...)
	}
	return frameMeta{
		ID: id, W: int32(w), H: int32(h), Bytes: len(body), Quality: quality,
		Type: "tiles", Tw: tile, Th: tile, Tiles: refs,
	}, body, nil
}
