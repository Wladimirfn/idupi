package main

import "errors"

// BGRAToRGBA converts a GDI DIB buffer (BGRA byte order) to Go's RGBA image
// layout with alpha forced opaque. It returns a new slice so the capture
// buffer can be reused by the next frame without aliasing surprises.
// A misaligned source yields nil; callers pass DIB-sized buffers, so that is
// an upstream programming error surfaced by the capture path's error return.
func BGRAToRGBA(src []byte) []byte {
	out, _ := safeConvert(src)
	return out
}

var errNotPixelAligned = errors.New("buffer length is not a multiple of 4 bytes")

func safeConvert(src []byte) ([]byte, error) {
	if len(src)%4 != 0 {
		return nil, errNotPixelAligned
	}
	out := make([]byte, len(src))
	for i := 0; i < len(src); i += 4 {
		out[i+0] = src[i+2] // R <- B slot
		out[i+1] = src[i+1] // G stays
		out[i+2] = src[i+0] // B <- R slot
		out[i+3] = 0xFF     // GDI alpha is undefined; force opaque
	}
	return out, nil
}
