//go:build windows

package main

import (
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var (
	user32 = syscall.NewLazyDLL("user32.dll")
	gdi32  = syscall.NewLazyDLL("gdi32.dll")
	shcore = syscall.NewLazyDLL("shcore.dll")

	procEnumDisplayMonitors = user32.NewProc("EnumDisplayMonitors")
	procGetMonitorInfoW     = user32.NewProc("GetMonitorInfoW")

	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procBitBlt             = gdi32.NewProc("BitBlt")
	procStretchBlt         = gdi32.NewProc("StretchBlt")
	procSetStretchBltMode  = gdi32.NewProc("SetStretchBltMode")
	procSetBrushOrgEx      = gdi32.NewProc("SetBrushOrgEx")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procDeleteDC           = gdi32.NewProc("DeleteDC")

	procGetCursorInfo = user32.NewProc("GetCursorInfo")
	procGetIconInfo   = user32.NewProc("GetIconInfo")
	procDrawIconEx    = user32.NewProc("DrawIconEx")
	procSendInput     = user32.NewProc("SendInput")

	procGetDC            = user32.NewProc("GetDC")
	procReleaseDC        = user32.NewProc("ReleaseDC")
	procGetDpiForMonitor = shcore.NewProc("GetDpiForMonitor")
)

const (
	srcCopy      = 0x00CC0020
	halftoneMode = 4 // smooth scaling; COLORONCOLOR (3) drops pixels instead
	dpiEffective = 0 // MDT_EFFECTIVE_DPI for GetDpiForMonitor

	cursorShowing = 0x00000001 // CURSORINFO.flags: the pointer is visible
	diNormal      = 0x0003     // DrawIconEx: image and mask, the usual draw
)

// CURSORINFO / ICONINFO, laid out to match the Win32 headers exactly --
// the padding after YHotspot is what aligns the handles on 64-bit.
type cursorInfo struct {
	CbSize      uint32
	Flags       uint32
	HCursor     uintptr
	PtScreenPos point
}

type iconInfo struct {
	FIcon    int32
	XHotspot uint32
	YHotspot uint32
	HbmMask  uintptr
	HbmColor uintptr
}

type point struct{ X, Y int32 }

type rect struct{ Left, Top, Right, Bottom int32 }

type monitorInfoEx struct {
	CbSize    uint32
	RcMonitor rect
	RcWork    rect
	DwFlags   uint32
	SzDevice  [32]uint16
}

type bitmapInfoHeader struct {
	BiSize                           uint32
	BiWidth, BiHeight                int32
	BiPlanes, BiBitCount             uint16
	BiCompression, BiSizeImage       uint32
	BiXPelsPerMeter, BiYPelsPerMeter int32
	BiClrUsed, BiClrImportant        uint32
}

type bitmapInfo struct {
	Header bitmapInfoHeader
	Colors [3]uint32
}

// Monitor is the wire shape for the "list" command response.
type Monitor struct {
	ID          int     `json:"id"`
	Name        string  `json:"name"`
	Primary     bool    `json:"primary"`
	X           int32   `json:"x"`
	Y           int32   `json:"y"`
	Width       int32   `json:"width"`
	Height      int32   `json:"height"`
	ScaleFactor float64 `json:"scaleFactor"`
}

type monitorEntry struct {
	mi  monitorInfoEx
	dpi uint32
}

var (
	enumMu            sync.Mutex
	enumScratch       []monitorEntry
	procGetSystemMetrics = user32.NewProc("GetSystemMetrics")
)

var enumMonitorsCb = syscall.NewCallback(func(hMon, hdc uintptr, lprc *rect, data uintptr) uintptr {
	var mi monitorInfoEx
	mi.CbSize = uint32(unsafe.Sizeof(mi))
	if r, _, _ := procGetMonitorInfoW.Call(hMon, uintptr(unsafe.Pointer(&mi))); r != 0 {
		e := monitorEntry{mi: mi, dpi: 96}
		var dx, dy uint32
		if r, _, _ := procGetDpiForMonitor.Call(hMon, dpiEffective,
			uintptr(unsafe.Pointer(&dx)), uintptr(unsafe.Pointer(&dy))); r == 0 && dx > 0 {
			e.dpi = dx
		}
		enumScratch = append(enumScratch, e)
	}
	return 1
})

func enumerateMonitors() ([]Monitor, error) {
	enumMu.Lock()
	defer enumMu.Unlock()

	var entries []monitorEntry
	for attempt := 0; attempt < 12; attempt++ {
		enumScratch = enumScratch[:0]
		r, _, _ := procEnumDisplayMonitors.Call(0, 0, enumMonitorsCb, 0)
		if r != 0 && len(enumScratch) > 0 {
			entries = make([]monitorEntry, len(enumScratch))
			copy(entries, enumScratch)
			break
		}
		time.Sleep(35 * time.Millisecond)
	}

	if len(entries) == 0 {
		cx, _, _ := procGetSystemMetrics.Call(0) // SM_CXSCREEN
		cy, _, _ := procGetSystemMetrics.Call(1) // SM_CYSCREEN
		w := int32(cx)
		h := int32(cy)
		if w <= 0 {
			w = 1920
		}
		if h <= 0 {
			h = 1080
		}
		return []Monitor{
			{
				ID:          0,
				Name:        `\\.\DISPLAY1`,
				Primary:     true,
				X:           0,
				Y:           0,
				Width:       w,
				Height:      h,
				ScaleFactor: 1.0,
			},
		}, nil
	}

	out := make([]Monitor, 0, len(entries))
	for i, e := range entries {
		out = append(out, Monitor{
			ID:          i,
			Name:        syscall.UTF16ToString(e.mi.SzDevice[:]),
			Primary:     e.mi.DwFlags&1 == 1,
			X:           e.mi.RcMonitor.Left,
			Y:           e.mi.RcMonitor.Top,
			Width:       e.mi.RcMonitor.Right - e.mi.RcMonitor.Left,
			Height:      e.mi.RcMonitor.Bottom - e.mi.RcMonitor.Top,
			ScaleFactor: float64(e.dpi) / 96.0,
		})
	}
	return out, nil
}
