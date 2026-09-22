//go:build windows

package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"syscall"
	"time"
	"unsafe"
)

var (
	ole32                = syscall.NewLazyDLL("ole32.dll")
	procCoInitializeEx   = ole32.NewProc("CoInitializeEx")
	procCoCreateInstance = ole32.NewProc("CoCreateInstance")
	procCoTaskMemFree    = ole32.NewProc("CoTaskMemFree")
)

type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

var (
	clsidMMDeviceEnumerator = guid{0xBCDE0395, 0xE52F, 0x467C, [8]byte{0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}}
	iidIMMDeviceEnumerator  = guid{0xA95664D2, 0x9614, 0x4F35, [8]byte{0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}}
	iidIAudioClient         = guid{0x1CB9AD4C, 0xDBFA, 0x4C32, [8]byte{0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}}
	iidIAudioCaptureClient  = guid{0xC8ADBD64, 0xE71E, 0x48A0, [8]byte{0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}}
)

const (
	clsctxAll                 = 0x17
	eRender                   = 0
	eConsole                  = 0
	audclntSharemodeShared    = 0
	audclntStreamflagsLoopback = 0x00020000
	audclntBufferflagsSilent  = 0x2
)

func comVtable(obj uintptr, index int) uintptr {
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	return *(*uintptr)(unsafe.Pointer(vtbl + uintptr(index)*unsafe.Sizeof(uintptr(0))))
}

func comRelease(obj uintptr) {
	if obj != 0 {
		syscall.SyscallN(comVtable(obj, 2), obj)
	}
}

func runAudioLoopback(out io.Writer) error {
	procCoInitializeEx.Call(0, 0)

	var enumerator uintptr
	hr, _, _ := procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidMMDeviceEnumerator)),
		0,
		clsctxAll,
		uintptr(unsafe.Pointer(&iidIMMDeviceEnumerator)),
		uintptr(unsafe.Pointer(&enumerator)),
	)
	if int32(hr) < 0 || enumerator == 0 {
		return fmt.Errorf("CoCreateInstance IMMDeviceEnumerator failed: 0x%x", uint32(hr))
	}
	defer comRelease(enumerator)

	// IMMDeviceEnumerator::GetDefaultAudioEndpoint (vtable 4)
	var device uintptr
	hr, _, _ = syscall.SyscallN(
		comVtable(enumerator, 4),
		enumerator,
		eRender,
		eConsole,
		uintptr(unsafe.Pointer(&device)),
	)
	if int32(hr) < 0 || device == 0 {
		return fmt.Errorf("GetDefaultAudioEndpoint failed: 0x%x", uint32(hr))
	}
	defer comRelease(device)

	// IMMDevice::Activate (vtable 3) -> IAudioClient
	var audioClient uintptr
	hr, _, _ = syscall.SyscallN(
		comVtable(device, 3),
		device,
		uintptr(unsafe.Pointer(&iidIAudioClient)),
		clsctxAll,
		0,
		uintptr(unsafe.Pointer(&audioClient)),
	)
	if int32(hr) < 0 || audioClient == 0 {
		return fmt.Errorf("IMMDevice::Activate IAudioClient failed: 0x%x", uint32(hr))
	}
	defer comRelease(audioClient)

	// IAudioClient::GetMixFormat (vtable 8)
	var pwfx uintptr
	hr, _, _ = syscall.SyscallN(
		comVtable(audioClient, 8),
		audioClient,
		uintptr(unsafe.Pointer(&pwfx)),
	)
	if int32(hr) < 0 || pwfx == 0 {
		return fmt.Errorf("GetMixFormat failed: 0x%x", uint32(hr))
	}
	defer procCoTaskMemFree.Call(pwfx)

	hdr := unsafe.Slice((*byte)(unsafe.Pointer(pwfx)), 18)
	channels := int(binary.LittleEndian.Uint16(hdr[2:4]))
	sampleRate := int(binary.LittleEndian.Uint32(hdr[4:8]))
	blockAlign := int(binary.LittleEndian.Uint16(hdr[12:14]))
	bitsPerSample := int(binary.LittleEndian.Uint16(hdr[14:16]))
	if channels < 1 || sampleRate < 8000 || blockAlign < 2 {
		return fmt.Errorf("unexpected mix format: ch=%d sr=%d bits=%d", channels, sampleRate, bitsPerSample)
	}

	// IAudioClient::Initialize (vtable 3) with 50ms buffer (500,000 * 100ns)
	hr, _, _ = syscall.SyscallN(
		comVtable(audioClient, 3),
		audioClient,
		audclntSharemodeShared,
		audclntStreamflagsLoopback,
		500000,
		0,
		pwfx,
		0,
	)
	if int32(hr) < 0 {
		return fmt.Errorf("IAudioClient::Initialize loopback failed: 0x%x", uint32(hr))
	}

	// IAudioClient::GetService (vtable 14) -> IAudioCaptureClient
	var captureClient uintptr
	hr, _, _ = syscall.SyscallN(
		comVtable(audioClient, 14),
		audioClient,
		uintptr(unsafe.Pointer(&iidIAudioCaptureClient)),
		uintptr(unsafe.Pointer(&captureClient)),
	)
	if int32(hr) < 0 || captureClient == 0 {
		return fmt.Errorf("GetService IAudioCaptureClient failed: 0x%x", uint32(hr))
	}
	defer comRelease(captureClient)

	// IAudioClient::Start (vtable 10)
	hr, _, _ = syscall.SyscallN(comVtable(audioClient, 10), audioClient)
	if int32(hr) < 0 {
		return fmt.Errorf("IAudioClient::Start failed: 0x%x", uint32(hr))
	}
	defer syscall.SyscallN(comVtable(audioClient, 11), audioClient)

	outChannels := 2
	if channels == 1 {
		outChannels = 1
	}

	bw := bufio.NewWriterSize(out, 16384)
	headerLine, _ := json.Marshal(map[string]any{
		"format":     "s16le",
		"sampleRate": sampleRate,
		"channels":   outChannels,
	})
	if _, err := bw.Write(append(headerLine, '\n')); err != nil {
		return err
	}
	if err := bw.Flush(); err != nil {
		return err
	}

	pcmBuf := make([]byte, 0, 16384)

	for {
		var packetLength uint32
		// IAudioCaptureClient::GetNextPacketSize (vtable 5)
		hr, _, _ = syscall.SyscallN(
			comVtable(captureClient, 5),
			captureClient,
			uintptr(unsafe.Pointer(&packetLength)),
		)
		if int32(hr) < 0 {
			return fmt.Errorf("GetNextPacketSize failed: 0x%x", uint32(hr))
		}

		if packetLength == 0 {
			time.Sleep(8 * time.Millisecond)
			continue
		}

		for packetLength > 0 {
			var pData uintptr
			var numFrames uint32
			var flags uint32

			// IAudioCaptureClient::GetBuffer (vtable 3)
			hr, _, _ = syscall.SyscallN(
				comVtable(captureClient, 3),
				captureClient,
				uintptr(unsafe.Pointer(&pData)),
				uintptr(unsafe.Pointer(&numFrames)),
				uintptr(unsafe.Pointer(&flags)),
				0,
				0,
			)
			if int32(hr) < 0 {
				return fmt.Errorf("GetBuffer failed: 0x%x", uint32(hr))
			}

			frames := int(numFrames)
			if frames > 0 {
				needBytes := frames * outChannels * 2
				if cap(pcmBuf) < needBytes {
					pcmBuf = make([]byte, needBytes)
				} else {
					pcmBuf = pcmBuf[:needBytes]
				}

				if (flags&audclntBufferflagsSilent) != 0 || pData == 0 {
					for i := range pcmBuf {
						pcmBuf[i] = 0
					}
				} else if bitsPerSample == 32 {
					src := unsafe.Slice((*float32)(unsafe.Pointer(pData)), frames*channels)
					for f := 0; f < frames; f++ {
						base := f * channels
						for c := 0; c < outChannels; c++ {
							chIdx := c
							if chIdx >= channels {
								chIdx = 0
							}
							val := float64(src[base+chIdx])
							if math.IsNaN(val) {
								val = 0
							} else if val > 1.0 {
								val = 1.0
							} else if val < -1.0 {
								val = -1.0
							}
							s16 := int16(val * 32767.0)
							off := (f*outChannels + c) * 2
							binary.LittleEndian.PutUint16(pcmBuf[off:off+2], uint16(s16))
						}
					}
				} else if bitsPerSample == 16 {
					src := unsafe.Slice((*int16)(unsafe.Pointer(pData)), frames*channels)
					for f := 0; f < frames; f++ {
						base := f * channels
						for c := 0; c < outChannels; c++ {
							chIdx := c
							if chIdx >= channels {
								chIdx = 0
							}
							off := (f*outChannels + c) * 2
							binary.LittleEndian.PutUint16(pcmBuf[off:off+2], uint16(src[base+chIdx]))
						}
					}
				}

				syscall.SyscallN(comVtable(captureClient, 4), captureClient, uintptr(numFrames))

				if _, err := bw.Write(pcmBuf); err != nil {
					return err
				}
				if err := bw.Flush(); err != nil {
					return err
				}
			} else {
				syscall.SyscallN(comVtable(captureClient, 4), captureClient, uintptr(numFrames))
			}

			hr, _, _ = syscall.SyscallN(
				comVtable(captureClient, 5),
				captureClient,
				uintptr(unsafe.Pointer(&packetLength)),
			)
			if int32(hr) < 0 {
				return fmt.Errorf("GetNextPacketSize loop failed: 0x%x", uint32(hr))
			}
		}
	}
}
