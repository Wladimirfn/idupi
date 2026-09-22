//go:build windows

package main

import (
	"fmt"
	"syscall"
	"time"
	"unsafe"
)

const (
	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

var (
	kernel32                     = syscall.NewLazyDLL("kernel32.dll")
	procOpenClipboard            = user32.NewProc("OpenClipboard")
	procCloseClipboard           = user32.NewProc("CloseClipboard")
	procEmptyClipboard           = user32.NewProc("EmptyClipboard")
	procGetClipboardData         = user32.NewProc("GetClipboardData")
	procSetClipboardData         = user32.NewProc("SetClipboardData")
	procIsClipboardFormatAvail   = user32.NewProc("IsClipboardFormatAvailable")
	procGlobalAlloc              = kernel32.NewProc("GlobalAlloc")
	procGlobalFree               = kernel32.NewProc("GlobalFree")
	procGlobalLock               = kernel32.NewProc("GlobalLock")
	procGlobalUnlock             = kernel32.NewProc("GlobalUnlock")
)

func openClipboardWithRetry() error {
	for i := 0; i < 8; i++ {
		r, _, _ := procOpenClipboard.Call(0)
		if r != 0 {
			return nil
		}
		time.Sleep(5 * time.Millisecond)
	}
	return fmt.Errorf("OpenClipboard busy")
}

func getClipboardText() (string, error) {
	avail, _, _ := procIsClipboardFormatAvail.Call(cfUnicodeText)
	if avail == 0 {
		return "", nil
	}
	if err := openClipboardWithRetry(); err != nil {
		return "", err
	}
	defer procCloseClipboard.Call()

	hMem, _, _ := procGetClipboardData.Call(cfUnicodeText)
	if hMem == 0 {
		return "", nil
	}
	ptr, _, _ := procGlobalLock.Call(hMem)
	if ptr == 0 {
		return "", nil
	}
	defer procGlobalUnlock.Call(hMem)

	// Read null-terminated UTF-16 slice up to 1MB characters
	const maxChars = 1024 * 1024
	u16 := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), maxChars)
	n := 0
	for n < maxChars && u16[n] != 0 {
		n++
	}
	return syscall.UTF16ToString(u16[:n]), nil
}

func setClipboardText(text string) error {
	u16, err := syscall.UTF16FromString(text)
	if err != nil {
		return err
	}
	if err := openClipboardWithRetry(); err != nil {
		return err
	}
	defer procCloseClipboard.Call()

	procEmptyClipboard.Call()

	sizeBytes := uintptr(len(u16) * 2)
	hMem, _, errSys := procGlobalAlloc.Call(gmemMoveable, sizeBytes)
	if hMem == 0 {
		return fmt.Errorf("GlobalAlloc failed: %v", errSys)
	}

	ptr, _, errSys := procGlobalLock.Call(hMem)
	if ptr == 0 {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("GlobalLock failed: %v", errSys)
	}

	dst := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(u16))
	copy(dst, u16)
	procGlobalUnlock.Call(hMem)

	r, _, errSys := procSetClipboardData.Call(cfUnicodeText, hMem)
	if r == 0 {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("SetClipboardData failed: %v", errSys)
	}
	return nil
}
