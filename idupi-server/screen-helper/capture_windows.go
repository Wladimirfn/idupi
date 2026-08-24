//go:build windows

package main

import (
	"bytes"
	"fmt"
	"image"
	"image/jpeg"
	"unsafe"
)

// captureJPEG captures the monitor rect scaled to the target size and
// encodes it as ONE JPEG at the requested quality -- the legacy path, still
// used anywhere a full frame is wanted without tile bookkeeping.
func captureJPEG(m Monitor, targetW, targetH, quality int) ([]byte, error) {
	img, err := captureRGBA(m, targetW, targetH)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		return nil, fmt.Errorf("jpeg encode failed: %v", err)
	}
	return buf.Bytes(), nil
}

// captureRGBA captures the monitor rect scaled to the target size in a
// single StretchBlt (HALFTONE, so downscaling averages instead of dropping
// pixels) and returns top-down RGBA pixels. Scaling at capture time shrinks
// the BGRA read, the conversion and every later encode together — never
// touch more pixels than the receiver displays.
func captureRGBA(m Monitor, targetW, targetH int) (*image.RGBA, error) {
	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, fmt.Errorf("GetDC failed")
	}
	defer procReleaseDC.Call(0, screenDC)

	memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return nil, fmt.Errorf("CreateCompatibleDC failed")
	}
	defer procDeleteDC.Call(memDC)

	tw, th := int32(targetW), int32(targetH)
	var bi bitmapInfo
	bi.Header.BiSize = uint32(unsafe.Sizeof(bi.Header))
	bi.Header.BiWidth = tw
	bi.Header.BiHeight = -th // negative = top-down rows
	bi.Header.BiPlanes = 1
	bi.Header.BiBitCount = 32

	var bits unsafe.Pointer
	hBitmap, _, _ := procCreateDIBSection.Call(
		memDC, uintptr(unsafe.Pointer(&bi)), 0,
		uintptr(unsafe.Pointer(&bits)), 0, 0)
	if hBitmap == 0 || bits == nil {
		return nil, fmt.Errorf("CreateDIBSection failed")
	}

	old, _, _ := procSelectObject.Call(memDC, hBitmap)
	defer func() {
		procSelectObject.Call(memDC, old)
		procDeleteObject.Call(hBitmap)
	}()

	// HALFTONE wants SetBrushOrgEx afterwards or stretched output can show
	// brush-alignment artifacts on some drivers.
	procSetStretchBltMode.Call(memDC, halftoneMode)
	procSetBrushOrgEx.Call(memDC, 0, 0, 0)

	if r, _, err := procStretchBlt.Call(
		memDC, 0, 0, uintptr(tw), uintptr(th),
		screenDC, uintptr(m.X), uintptr(m.Y), uintptr(m.Width), uintptr(m.Height),
		srcCopy); r == 0 {
		return nil, fmt.Errorf("StretchBlt failed: %v", err)
	}
	drawCursor(memDC, m, int(tw), int(th)) // composite the pointer Windows never blits

	src := unsafe.Slice((*byte)(bits), uintptr(tw)*uintptr(th)*4)
	img := image.NewRGBA(image.Rect(0, 0, int(tw), int(th)))
	copy(img.Pix, BGRAToRGBA(src))

	return img, nil
}
