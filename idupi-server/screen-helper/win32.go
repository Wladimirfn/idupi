//go:build windows

package main

import (
	"fmt"
	"syscall"
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

	procGetDC            = user32.NewProc("GetDC")
	procReleaseDC        = user32.NewProc("ReleaseDC")
	procGetDpiForMonitor = shcore.NewProc("GetDpiForMonitor")
)

const (
	srcCopy      = 0x00CC0020
	halftoneMode = 4 // smooth scaling; COLORONCOLOR (3) drops pixels instead
	dpiEffective = 0 // MDT_EFFECTIVE_DPI for GetDpiForMonitor
)

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

func enumerateMonitors() ([]Monitor, error) {
	type entry struct {
		mi  monitorInfoEx
		dpi uint32
	}
	var entries []entry
	cb := syscall.NewCallback(func(hMon, hdc uintptr, lprc *rect, data uintptr) uintptr {
		var mi monitorInfoEx
		mi.CbSize = uint32(unsafe.Sizeof(mi))
		if r, _, _ := procGetMonitorInfoW.Call(hMon, uintptr(unsafe.Pointer(&mi))); r != 0 {
			e := entry{mi: mi, dpi: 96}
			var dx, dy uint32
			if r, _, _ := procGetDpiForMonitor.Call(hMon, dpiEffective,
				uintptr(unsafe.Pointer(&dx)), uintptr(unsafe.Pointer(&dy))); r == 0 && dx > 0 {
				e.dpi = dx
			}
			entries = append(entries, e)
		}
		return 1
	})
	if r, _, err := procEnumDisplayMonitors.Call(0, 0, cb, 0); r == 0 {
		return nil, fmt.Errorf("EnumDisplayMonitors failed: %v", err)
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
