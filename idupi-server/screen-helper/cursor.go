package main

import "unsafe"

// cursorDrawPoint maps the mouse pointer's virtual-desktop position onto a
// scaled capture of one monitor, returning the top-left corner to draw the
// icon at and whether it belongs on this capture at all.
//
// Three things it has to get right, and all three have bitten this project:
//   - The monitor may start at a negative origin (the user's left screen is at
//     x=-1920), so the position is made monitor-relative before anything else.
//   - The capture is scaled to the viewer's viewport, so the position scales
//     with it.
//   - The hotspot does NOT scale: the icon is drawn at its native size, so its
//     offset is in icon pixels. Scaling it would drift the tip off the point
//     the user is actually aiming at.
func cursorDrawPoint(m Monitor, curX, curY, hotX, hotY, targetW, targetH int) (int, int, bool) {
	if m.Width <= 0 || m.Height <= 0 {
		return 0, 0, false
	}

	relX := curX - int(m.X)
	relY := curY - int(m.Y)
	if relX < 0 || relY < 0 || relX >= int(m.Width) || relY >= int(m.Height) {
		return 0, 0, false // pointer is on another monitor
	}

	scaledX := relX * targetW / int(m.Width)
	scaledY := relY * targetH / int(m.Height)

	return scaledX - hotX, scaledY - hotY, true
}

// drawCursor composites the mouse pointer onto an already-captured memory DC.
// BitBlt/StretchBlt never include the cursor -- Windows composites it on top
// at present time -- so without this the remote picture shows no pointer at
// all and input aiming becomes guesswork.
func drawCursor(memDC uintptr, m Monitor, targetW, targetH int) {
	var ci cursorInfo
	ci.CbSize = uint32(unsafe.Sizeof(ci))
	if r, _, _ := procGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci))); r == 0 {
		return
	}
	if ci.Flags&cursorShowing == 0 || ci.HCursor == 0 {
		return // hidden or shapeless pointer: nothing to composite
	}
	hotX, hotY := 0, 0
	var ii iconInfo
	if r, _, _ := procGetIconInfo.Call(ci.HCursor, uintptr(unsafe.Pointer(&ii))); r != 0 {
		hotX, hotY = int(ii.XHotspot), int(ii.YHotspot)
		// GetIconInfo hands back two bitmaps owned by us; leak-free means gone.
		if ii.HbmMask != 0 {
			procDeleteObject.Call(ii.HbmMask)
		}
		if ii.HbmColor != 0 {
			procDeleteObject.Call(ii.HbmColor)
		}
	} // failing the query keeps the default tip (0,0): better than no pointer
	x, y, ok := cursorDrawPoint(m, int(ci.PtScreenPos.X), int(ci.PtScreenPos.Y), hotX, hotY, targetW, targetH)
	if !ok {
		return // pointer lives on another monitor
	}
	procDrawIconEx.Call(memDC, uintptr(int32(x)), uintptr(int32(y)), ci.HCursor, 0, 0, 0, 0, diNormal)
}
