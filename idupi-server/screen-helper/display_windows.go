//go:build windows

package main

import (
	"fmt"
	"syscall"
	"unsafe"
)

var (
	procSetDisplayConfig       = user32.NewProc("SetDisplayConfig")
	procEnumDisplaySettingsW   = user32.NewProc("EnumDisplaySettingsW")
	procChangeDisplaySettingsW = user32.NewProc("ChangeDisplaySettingsExW")
)

const (
	sdcTopologyInternal = 0x00000001
	sdcTopologyClone    = 0x00000002
	sdcTopologyExtend   = 0x00000004
	sdcApply            = 0x00000080

	enumCurrentSettings = 0xFFFFFFFF // -1

	dmPelsWidth  = 0x00080000
	dmPelsHeight = 0x00100000
)

// devModeW matches the Win32 DEVMODEW structure (220 bytes on Windows).
type devModeW struct {
	DmDeviceName       [32]uint16
	DmSpecVersion      uint16
	DmDriverVersion    uint16
	DmSize             uint16
	DmDriverExtra      uint16
	DmFields           uint32
	DmPositionX        int32
	DmPositionY        int32
	DmDisplayOrient    uint32
	DmDisplayFixedOut  uint32
	DmColor            int16
	DmDuplex           int16
	DmYResolution      int16
	DmTTOption         int16
	DmCollate          int16
	DmFormName         [32]uint16
	DmLogPixels        uint16
	DmBitsPerPel       uint32
	DmPelsWidth        uint32
	DmPelsHeight       uint32
	DmDisplayFlags     uint32
	DmDisplayFrequency uint32
	DmICMMethod        uint32
	DmICMIntent        uint32
	DmMediaType        uint32
	DmDitherType       uint32
	DmReserved1        uint32
	DmReserved2        uint32
	DmPanningWidth     uint32
	DmPanningHeight    uint32
}

func setDisplayTopologyExtend() error {
	r, _, _ := procSetDisplayConfig.Call(0, 0, 0, 0, uintptr(sdcTopologyExtend|sdcApply))
	if r != 0 {
		return fmt.Errorf("SetDisplayConfig(EXTEND) returned code %d", r)
	}
	return nil
}

func setDisplayTopologyInternal() error {
	r, _, _ := procSetDisplayConfig.Call(0, 0, 0, 0, uintptr(sdcTopologyInternal|sdcApply))
	if r != 0 {
		return fmt.Errorf("SetDisplayConfig(INTERNAL) returned code %d", r)
	}
	return nil
}

func resizeDisplay(deviceName string, width, height int) error {
	if deviceName == "" || width < 640 || height < 480 {
		return nil
	}
	devPtr, err := syscall.UTF16PtrFromString(deviceName)
	if err != nil {
		return err
	}

	var dm devModeW
	dm.DmSize = uint16(unsafe.Sizeof(dm))

	r, _, _ := procEnumDisplaySettingsW.Call(
		uintptr(unsafe.Pointer(devPtr)),
		uintptr(enumCurrentSettings),
		uintptr(unsafe.Pointer(&dm)),
	)
	if r == 0 {
		return fmt.Errorf("EnumDisplaySettingsW failed for %s", deviceName)
	}

	if int(dm.DmPelsWidth) == width && int(dm.DmPelsHeight) == height {
		return nil
	}

	dm.DmPelsWidth = uint32(width)
	dm.DmPelsHeight = uint32(height)
	dm.DmFields = dmPelsWidth | dmPelsHeight

	ret, _, _ := procChangeDisplaySettingsW.Call(
		uintptr(unsafe.Pointer(devPtr)),
		uintptr(unsafe.Pointer(&dm)),
		0,
		0,
		0,
	)
	if int32(ret) != 0 {
		return fmt.Errorf("ChangeDisplaySettingsExW returned %d", int32(ret))
	}
	return nil
}
