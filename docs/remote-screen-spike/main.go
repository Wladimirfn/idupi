//go:build windows

// Spike: can a compiled Go binary enumerate monitors, capture a screen and
// encode it, using nothing but the standard library and syscall?
package main

import (
	"fmt"
	"image"
	"image/jpeg"
	"os"
	"time"
	"unsafe"
)

import "syscall"

var (
	user32 = syscall.NewLazyDLL("user32.dll")
	gdi32  = syscall.NewLazyDLL("gdi32.dll")

	procEnumDisplayMonitors = user32.NewProc("EnumDisplayMonitors")
	procGetMonitorInfoW     = user32.NewProc("GetMonitorInfoW")
	procGetDC               = user32.NewProc("GetDC")
	procReleaseDC           = user32.NewProc("ReleaseDC")

	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procBitBlt             = gdi32.NewProc("BitBlt")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procDeleteDC           = gdi32.NewProc("DeleteDC")
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
	BiSize                                    uint32
	BiWidth, BiHeight                         int32
	BiPlanes, BiBitCount                      uint16
	BiCompression, BiSizeImage                uint32
	BiXPelsPerMeter, BiYPelsPerMeter          int32
	BiClrUsed, BiClrImportant                 uint32
}

type bitmapInfo struct {
	Header bitmapInfoHeader
	Colors [3]uint32
}

type monitor struct {
	Name    string
	Bounds  rect
	Primary bool
}

func listMonitors() []monitor {
	var out []monitor
	cb := syscall.NewCallback(func(hMon, hdc uintptr, lprc *rect, data uintptr) uintptr {
		var mi monitorInfoEx
		mi.CbSize = uint32(unsafe.Sizeof(mi))
		r, _, _ := procGetMonitorInfoW.Call(hMon, uintptr(unsafe.Pointer(&mi)))
		if r != 0 {
			out = append(out, monitor{
				Name:    syscall.UTF16ToString(mi.SzDevice[:]),
				Bounds:  mi.RcMonitor,
				Primary: mi.DwFlags&1 == 1,
			})
		}
		return 1
	})
	procEnumDisplayMonitors.Call(0, 0, cb, 0)
	return out
}

// Counts bytes without keeping them, so encoding cost is measured alone.
type bytesBuffer struct{ n int }

func (b *bytesBuffer) Write(p []byte) (int, error) { b.n += len(p); return len(p), nil }

var pendingCleanup []func()

func freeRaw() {
	for _, f := range pendingCleanup {
		f()
	}
	pendingCleanup = nil
}

func captureRaw(b rect) ([]byte, int, int, error) {
	w, h := int(b.Right-b.Left), int(b.Bottom-b.Top)
	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, 0, 0, fmt.Errorf("GetDC failed")
	}
	memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
	var bi bitmapInfo
	bi.Header.BiSize = uint32(unsafe.Sizeof(bi.Header))
	bi.Header.BiWidth = int32(w)
	bi.Header.BiHeight = int32(-h)
	bi.Header.BiPlanes = 1
	bi.Header.BiBitCount = 32
	var bits unsafe.Pointer
	hBitmap, _, _ := procCreateDIBSection.Call(memDC, uintptr(unsafe.Pointer(&bi)), 0,
		uintptr(unsafe.Pointer(&bits)), 0, 0)
	if hBitmap == 0 {
		return nil, 0, 0, fmt.Errorf("CreateDIBSection failed")
	}
	old, _, _ := procSelectObject.Call(memDC, hBitmap)
	const srcCopy = 0x00CC0020
	r, _, _ := procBitBlt.Call(memDC, 0, 0, uintptr(w), uintptr(h),
		screenDC, uintptr(b.Left), uintptr(b.Top), srcCopy)
	if r == 0 {
		return nil, 0, 0, fmt.Errorf("BitBlt failed")
	}
	pendingCleanup = append(pendingCleanup, func() {
		procSelectObject.Call(memDC, old)
		procDeleteObject.Call(hBitmap)
		procDeleteDC.Call(memDC)
		procReleaseDC.Call(0, screenDC)
	})
	return unsafe.Slice((*byte)(bits), w*h*4), w, h, nil
}

func capture(b rect) (*image.RGBA, error) {
	w, h := int(b.Right-b.Left), int(b.Bottom-b.Top)

	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, fmt.Errorf("GetDC failed")
	}
	defer procReleaseDC.Call(0, screenDC)

	memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
	defer procDeleteDC.Call(memDC)

	var bi bitmapInfo
	bi.Header.BiSize = uint32(unsafe.Sizeof(bi.Header))
	bi.Header.BiWidth = int32(w)
	bi.Header.BiHeight = int32(-h) // top-down
	bi.Header.BiPlanes = 1
	bi.Header.BiBitCount = 32

	var bits unsafe.Pointer
	hBitmap, _, _ := procCreateDIBSection.Call(
		memDC, uintptr(unsafe.Pointer(&bi)), 0,
		uintptr(unsafe.Pointer(&bits)), 0, 0)
	if hBitmap == 0 {
		return nil, fmt.Errorf("CreateDIBSection failed")
	}
	defer procDeleteObject.Call(hBitmap)

	old, _, _ := procSelectObject.Call(memDC, hBitmap)
	defer procSelectObject.Call(memDC, old)

	const srcCopy = 0x00CC0020
	r, _, _ := procBitBlt.Call(memDC, 0, 0, uintptr(w), uintptr(h),
		screenDC, uintptr(b.Left), uintptr(b.Top), srcCopy)
	if r == 0 {
		return nil, fmt.Errorf("BitBlt failed")
	}

	src := unsafe.Slice((*byte)(bits), w*h*4)
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for i := 0; i < w*h; i++ {
		// GDI gives BGRA; Go wants RGBA.
		img.Pix[i*4+0] = src[i*4+2]
		img.Pix[i*4+1] = src[i*4+1]
		img.Pix[i*4+2] = src[i*4+0]
		img.Pix[i*4+3] = 255
	}
	return img, nil
}

func main() {
	mons := listMonitors()
	for i, m := range mons {
		fmt.Printf("monitor[%d] name=%s primary=%v x=%d y=%d w=%d h=%d\n",
			i, m.Name, m.Primary, m.Bounds.Left, m.Bounds.Top,
			m.Bounds.Right-m.Bounds.Left, m.Bounds.Bottom-m.Bounds.Top)
	}
	if len(mons) == 0 {
		fmt.Println("sin monitores")
		return
	}

	target := mons[0]
	for _, m := range mons {
		if m.Primary {
			target = m
		}
	}

	const frames = 15
	start := time.Now()
	var lastBytes int
	for i := 0; i < frames; i++ {
		img, err := capture(target.Bounds)
		if err != nil {
			fmt.Println("ERROR:", err)
			return
		}
		f, _ := os.CreateTemp("", "spike*.jpg")
		jpeg.Encode(f, img, &jpeg.Options{Quality: 55})
		st, _ := f.Stat()
		lastBytes = int(st.Size())
		f.Close()
		os.Remove(f.Name())
	}
	el := time.Since(start)
	fmt.Printf("captura+jpeg: %d ms/frame  ~%.1f fps  %d KB/frame\n",
		el.Milliseconds()/frames, float64(frames)/el.Seconds(), lastBytes/1024)
}
