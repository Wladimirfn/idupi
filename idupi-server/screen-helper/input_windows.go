//go:build windows

package main

import (
	"fmt"
	"sync"
	"time"
	"unsafe"
)

// Mouse input arrives as its own command class on the SAME binary protocol,
// but travels on a DEDICATED helper instance owned by the input route -- never
// queued behind capture work, because input latency is what this feature is
// judged on.

const (
	meMove      = 0x0001
	meLeftDown  = 0x0002
	meLeftUp    = 0x0004
	meRightDown = 0x0008
	meRightUp   = 0x0010
	meWheel     = 0x0800
	meAbsolute  = 0x8000
	meVirtual   = 0x4000
)

// INPUT/x64: DWORD type, union padding to align the payload, then MOUSEINPUT.
type mouseInput struct {
	Type      uint32
	_         uint32
	Dx        uint32
	Dy        uint32
	MouseData uint32
	DwFlags   uint32
	Time      uint32
	ExtraInfo uintptr
}

const keUnicode = 0x0004 // KEYEVENTF_UNICODE: WScan carries a UTF-16 unit

// INPUT/x64 with KEYBDINPUT payload. The trailing slack is NOT decoration:
// SendInput validates cbSize against sizeof(INPUT), whose size is set by the
// LARGEST union member (MOUSEINPUT, 40 bytes on x64). A bare KEYBDINPUT is
// 32 bytes, and EVERY keystroke fails with ERROR_INVALID_PARAMETER -- array
// stride must equal cbSize too, so this struct carries the full union width.
type keybdInput struct {
	Type      uint32
	_         uint32
	WVk       uint16
	WScan     uint16
	DwFlags   uint32
	Time      uint32
	ExtraInfo uintptr
	_         [8]byte // union slack up to MOUSEINPUT's 40-byte width
}

// inputRequest is one decoded stdin input command, already validated.
type inputRequest struct {
	Action       string // "move" | "down" | "up" | "scroll" | "relmove"
	Button       string // "" | "left" | "right"
	HasPos       bool   // true when NX/NY are present (absolute actions only)
	NX           float64
	NY           float64
	DX           float64 // relmove: cursor pixels to travel horizontally
	DY           float64 // relmove: cursor pixels to travel vertically
	MonitorIndex int
	Delta        int
}

// buildMouseInput turns a validated request into the SendInput payload using
// AbsolutePointer: coordinates arrive NORMALISED against the chosen monitor
// (never pixels -- the target machine's left monitor starts at x=-1920) and
// are resolved here into absolute virtual-desktop units.
func buildMouseInput(req inputRequest, monitors []Monitor) (*mouseInput, error) {
	if len(monitors) == 0 {
		return nil, fmt.Errorf("no monitors to aim at")
	}
	var m Monitor
	if req.MonitorIndex >= 0 && req.MonitorIndex < len(monitors) {
		m = monitors[req.MonitorIndex]
	} else {
		for _, cand := range monitors {
			if cand.Primary {
				m = cand
				break
			}
		}
	}

	in := &mouseInput{Type: 0} // INPUT_MOUSE

	switch req.Action {
	case "relmove":
		// Pad mode: deltas are screen pixels; a plain MOVE (no absolute
		// bits) travels relative to the cursor's current position and is
		// scaled by the OS pointer-speed setting like a physical mouse.
		in.Dx = uint32(int32(clampRelDelta(req.DX)))
		in.Dy = uint32(int32(clampRelDelta(req.DY)))
		in.DwFlags = meMove
	case "move", "down", "up":
		if !req.HasPos {
			// Coordless click: press/release at the cursor's CURRENT
			// position -- never a stray absolute move to (0,0).
			if req.Action != "down" && req.Action != "up" {
				return nil, fmt.Errorf("action %q needs x and y", req.Action)
			}
			in.DwFlags = buttonFlag(req.Button, req.Action == "down")
			return in, nil
		}
		bounds := Bounds{X: m.X, Y: m.Y, W: m.Width, H: m.Height}
		all := make([]Bounds, 0, len(monitors))
		for _, mo := range monitors {
			all = append(all, Bounds{X: mo.X, Y: mo.Y, W: mo.Width, H: mo.Height})
		}
		ax, ay := AbsolutePointer(bounds, VirtualBounds(all), req.NX, req.NY)
		in.Dx, in.Dy = ax, ay
		in.DwFlags = meMove | meAbsolute | meVirtual
		if req.Action != "move" {
			in.DwFlags |= buttonFlag(req.Button, req.Action == "down")
		}
	case "scroll":
		in.MouseData = uint32(int32(req.Delta))
		in.DwFlags = meWheel
	default:
		return nil, fmt.Errorf("unknown input action %q", req.Action)
	}
	return in, nil
}

// clampRelDelta bounds one relative delta to a sane range so a runaway
// client cannot slam the cursor across the virtual desktop in one event.
func clampRelDelta(v float64) float64 {
	const limit = 10000
	if v > limit {
		return limit
	}
	if v < -limit {
		return -limit
	}
	return v
}

// buildClickPair builds a COMPLETE click as a down+up pair. A click whose
// halves travel as separate wire commands wedges the physical mouse forever
// when the release is lost (the OS keeps the button logically pressed); the
// pair is sent through SendInput as ONE contiguous array so nothing can slip
// between press and release.
func buildClickPair(req inputRequest, monitors []Monitor) (*mouseInput, *mouseInput, error) {
	if len(monitors) == 0 {
		return nil, nil, fmt.Errorf("no monitors to aim at")
	}
	down := &mouseInput{Type: 0} // INPUT_MOUSE
	up := &mouseInput{Type: 0}

	// Aim first when a position came along; otherwise press wherever the
	// cursor already sits.
	if req.HasPos {
		var m Monitor
		if req.MonitorIndex >= 0 && req.MonitorIndex < len(monitors) {
			m = monitors[req.MonitorIndex]
		} else {
			for _, cand := range monitors {
				if cand.Primary {
					m = cand
					break
				}
			}
		}
		bounds := Bounds{X: m.X, Y: m.Y, W: m.Width, H: m.Height}
		all := make([]Bounds, 0, len(monitors))
		for _, mo := range monitors {
			all = append(all, Bounds{X: mo.X, Y: mo.Y, W: mo.Width, H: mo.Height})
		}
		ax, ay := AbsolutePointer(bounds, VirtualBounds(all), req.NX, req.NY)
		down.Dx, down.Dy = ax, ay
		down.DwFlags = meMove | meAbsolute | meVirtual
	}

	switch req.Button {
	case "right":
		down.DwFlags |= meRightDown
		up.DwFlags = meRightUp
	default:
		down.DwFlags |= meLeftDown
		up.DwFlags = meLeftUp
	}
	return down, up, nil
}

// executeClick sends the down+up pair atomically.
func executeClick(down, up *mouseInput) error {
	pair := [2]mouseInput{*down, *up}
	r, _, err := procSendInput.Call(2, uintptr(unsafe.Pointer(&pair[0])), unsafe.Sizeof(pair[0]))
	if r == 0 {
		return fmt.Errorf("SendInput click failed: %v", err)
	}
	return nil
}

// --- stuck-button watchdog -------------------------------------------------
//
// A remote press whose release never arrives wedges the PHYSICAL mouse
// OS-WIDE: SendInput state is global, so even killing this process cannot
// undo it. The hold tracker remembers remotely-pressed buttons and main()'s
// watchdog goroutine auto-releases anything held past the limit.

const holdLimit = 30 * time.Second

// heldButtons is the watchdog's state, fed by every mouse send below.
var heldButtons holdTracker

type holdTracker struct {
	mu        sync.Mutex
	left      bool
	right     bool
	heldSince time.Time
}

// note records what one sent mouse event did to the buttons.
func (h *holdTracker) note(flags uint32, now time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if flags&(meLeftDown|meRightDown) != 0 {
		if !h.left && !h.right {
			h.heldSince = now
		}
	}
	if flags&meLeftDown != 0 {
		h.left = true
	}
	if flags&meRightDown != 0 {
		h.right = true
	}
	if flags&(meLeftUp|meRightUp) != 0 {
		if flags&meLeftUp != 0 {
			h.left = false
		}
		if flags&meRightUp != 0 {
			h.right = false
		}
		if !h.left && !h.right {
			h.heldSince = time.Time{}
		}
	}
}

// expired reports the release flags for any button held past the limit and
// clears it from the tracker.
func (h *holdTracker) expired(now time.Time, limit time.Duration) (uint32, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !(h.left || h.right) || now.Sub(h.heldSince) < limit {
		return 0, false
	}
	var flags uint32
	if h.left {
		flags |= meLeftUp
		h.left = false
	}
	if h.right {
		flags |= meRightUp
		h.right = false
	}
	h.heldSince = time.Time{}
	return flags, true
}

func buttonFlag(button string, down bool) uint32 {
	switch button {
	case "right":
		if down {
			return meRightDown
		}
		return meRightUp
	default:
		if down {
			return meLeftDown
		}
		return meLeftUp
	}
}

// executeInput sends one mouse event through SendInput. One event per call is
// deliberate: each arrives on its own stdin command, so a click is two events
// (down, up) that the server may space arbitrarily apart.
func executeInput(in *mouseInput) error {
	const inputMouse = 0 // INPUT_MOUSE
	r, _, err := procSendInput.Call(1, uintptr(unsafe.Pointer(in)), unsafe.Sizeof(*in))
	_ = inputMouse
	if r == 0 {
		return fmt.Errorf("SendInput failed: %v", err)
	}
	return nil
}

// buildKeyInput prepares ONE COMPLETE keystroke (unicode char or virtual key).
// Realtime typing (hito 7) sends each key as it is pressed; folding down+up
// into a single helper command halves the wire round-trips per keystroke.
func buildKeyInput(action string, code int) (*keybdInput, error) {
	in := &keybdInput{Type: 1} // INPUT_KEYBOARD
	switch action {
	case "keychar":
		// Emoji arrive as surrogate PAIRS split across two events; each
		// UTF-16 unit rides its own keystroke and Windows reassembles them.
		if code < 1 || code > 0xFFFF {
			return nil, fmt.Errorf("keychar code %d is not a storable UTF-16 unit", code)
		}
		in.WScan = uint16(code)
		in.DwFlags = keUnicode
	case "keyvk":
		if code < 1 || code > 255 {
			return nil, fmt.Errorf("keyvk code %d is outside the virtual-key range", code)
		}
		in.WVk = uint16(code)
	default:
		return nil, fmt.Errorf("unknown key action %q", action)
	}
	return in, nil
}

// executeKeyInput presses AND releases the key: the unicode/vk down event
// followed by KEYEVENTF_KEYUP travel as ONE contiguous array so SendInput
// inserts them atomically -- no other input can slip between them.
func executeKeyInput(in *keybdInput) error {
	const keyEventfKeyUp = 0x0002
	pair := [2]keybdInput{*in, *in}
	pair[1].DwFlags |= keyEventfKeyUp
	r, _, err := procSendInput.Call(2, uintptr(unsafe.Pointer(&pair[0])), unsafe.Sizeof(pair[0]))
	if r == 0 {
		return fmt.Errorf("SendInput keystroke failed: %v", err)
	}
	return nil
}
