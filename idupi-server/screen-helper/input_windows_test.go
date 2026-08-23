package main

import (
	"testing"
	"time"
	"unsafe"
)

// SendInput validates cbSize against sizeof(INPUT), whose x64 size is set by
// the LARGEST union member (MOUSEINPUT, 40 bytes). A KEYBDINPUT-sized struct
// (32 bytes) makes EVERY keystroke fail with ERROR_INVALID_PARAMETER even
// though its own fields are laid out perfectly.
func TestKeybdInputIsPaddedToTheInputUnionSize(t *testing.T) {
	mouse := unsafe.Sizeof(mouseInput{})
	kbd := unsafe.Sizeof(keybdInput{})
	if kbd != mouse {
		t.Fatalf("keybdInput is %d bytes but INPUT union is %d: SendInput would reject every keystroke", kbd, mouse)
	}
}

// The pad mode sends RELATIVE cursor movement: deltas arrive in screen
// pixels and must ride a plain MOUSEEVENTF_MOVE with no absolute/virtual
// bits, or Windows would reinterpret them as coordinates.
func TestBuildMouseInputRelativeMoveUsesPlainMoveFlag(t *testing.T) {
	in, err := buildMouseInput(inputRequest{
		Action: "relmove",
		DX:     -120,
		DY:     40,
	}, []Monitor{{ID: 0, Primary: true, Width: 1920, Height: 1080}})
	if err != nil {
		t.Fatalf("relmove: %v", err)
	}
	if in.DwFlags != meMove {
		t.Fatalf("flags: got %#x, want plain %#x", in.DwFlags, meMove)
	}
	if int32(in.Dx) != -120 || int32(in.Dy) != 40 {
		t.Fatalf("deltas: got (%d,%d), want (-120,40)", int32(in.Dx), int32(in.Dy))
	}
}

// A pad tap is a click at the cursor's CURRENT position: no x/y on the wire,
// so the event must carry only the button flag -- never a stray absolute move.
func TestBuildMouseInputCoordlessClickKeepsCursorPosition(t *testing.T) {
	for _, tc := range []struct {
		action string
		button string
		want   uint32
	}{
		{"down", "left", meLeftDown},
		{"up", "left", meLeftUp},
		{"down", "right", meRightDown},
		{"up", "right", meRightUp},
	} {
		in, err := buildMouseInput(inputRequest{
			Action: tc.action,
			Button: tc.button,
		}, []Monitor{{ID: 0, Primary: true, Width: 1920, Height: 1080}})
		if err != nil {
			t.Fatalf("%s/%s: %v", tc.action, tc.button, err)
		}
		if in.DwFlags != tc.want {
			t.Fatalf("%s/%s flags: got %#x, want %#x", tc.action, tc.button, in.DwFlags, tc.want)
		}
		if in.Dx != 0 || in.Dy != 0 {
			t.Fatalf("%s/%s must not move: got (%d,%d)", tc.action, tc.button, in.Dx, in.Dy)
		}
	}
}

// Absolute clicks WITH coordinates keep their existing behaviour untouched.
func TestBuildMouseInputAbsoluteClickStillMoves(t *testing.T) {
	m := Monitor{ID: 0, Primary: true, X: 0, Y: 0, Width: 1920, Height: 1080}
	in, err := buildMouseInput(inputRequest{
		Action:       "down",
		Button:       "left",
		MonitorIndex: 0,
		NX:           0.5,
		NY:           0.5,
		HasPos:       true,
	}, []Monitor{m})
	if err != nil {
		t.Fatalf("absolute click: %v", err)
	}
	want := uint32(meMove | meAbsolute | meVirtual | meLeftDown)
	if in.DwFlags != want {
		t.Fatalf("flags: got %#x, want %#x", in.DwFlags, want)
	}
}

// Realtime typing (hito 7): one wire event is ONE complete keystroke --
// down+up inside the helper -- so a character halves its round-trips.
func TestBuildKeyInputUnicodeCharRidesScanCode(t *testing.T) {
	in, err := buildKeyInput("keychar", 'ñ')
	if err != nil {
		t.Fatalf("keychar: %v", err)
	}
	if in.WVk != 0 {
		t.Fatalf("wVk must stay 0 for unicode events, got %d", in.WVk)
	}
	if in.WScan != uint16('ñ') {
		t.Fatalf("wScan: got %d, want %d", in.WScan, 'ñ')
	}
	if in.DwFlags != keUnicode {
		t.Fatalf("flags: got %#x, want %#x", in.DwFlags, keUnicode)
	}
}

func TestBuildKeyInputVirtualKeyUsesWVk(t *testing.T) {
	in, err := buildKeyInput("keyvk", 0x0D) // VK_RETURN
	if err != nil {
		t.Fatalf("keyvk: %v", err)
	}
	if in.WVk != 0x0D {
		t.Fatalf("wVk: got %d, want 13", in.WVk)
	}
	if in.WScan != 0 {
		t.Fatalf("wScan must stay 0 for vk events, got %d", in.WScan)
	}
	if in.DwFlags != 0 {
		t.Fatalf("flags: got %#x, want none", in.DwFlags)
	}
}

func TestBuildKeyInputRejectsGarbage(t *testing.T) {
	for _, tc := range []struct {
		action string
		code   int
	}{
		{"keychar", 0},       // NUL is never a keystroke
		{"keychar", 0x10000}, // beyond UTF-16: unsupported
		{"keyvk", 0},         // 0 is not a key
		{"keyvk", 256},       // beyond the VK range
		{"unknown", 65},      // unknown action
	} {
		if _, err := buildKeyInput(tc.action, tc.code); err == nil {
			t.Fatalf("%s(%d): expected an error, got none", tc.action, tc.code)
		}
	}
}

// Emoji arrive as surrogate PAIRS split across two events; the helper must
// accept each UTF-16 unit and let Windows reassemble the pair.
func TestBuildKeyInputAcceptsSurrogateUnits(t *testing.T) {
	for _, code := range []int{0xD83D, 0xDE00} {
		if _, err := buildKeyInput("keychar", code); err != nil {
			t.Fatalf("keychar(%#x): %v", code, err)
		}
	}
}

// A lost release wedges the physical mouse OS-WIDE -- the worst failure this
// module can cause. The hold tracker is the watchdog: it remembers which
// buttons were pressed remotely and reports the ones held past the limit so
// main() can auto-release them.
func TestHoldTrackerWatchesPressedButtons(t *testing.T) {
	var h holdTracker
	now := time.Unix(1_000_000, 0)

	// Idle: nothing to release.
	if _, ok := h.expired(now, holdLimit); ok {
		t.Fatalf("nothing held, but expired reported a release")
	}

	// Press left at t=0.
	h.note(meLeftDown, now)
	if !h.left || h.right {
		t.Fatalf("left should be held, right not")
	}
	if _, ok := h.expired(now.Add(holdLimit-time.Second), holdLimit); ok {
		t.Fatalf("held under the limit must not report")
	}

	// Past the limit: report a LEFT release.
	flags, ok := h.expired(now.Add(holdLimit+time.Second), holdLimit)
	if !ok || flags != meLeftUp {
		t.Fatalf("expected left-up release, got %#x ok=%v", flags, ok)
	}

	// Right press then right release clears it.
	h.note(meRightDown, now)
	h.note(meRightUp, now.Add(time.Second))
	if h.right {
		t.Fatalf("right released but tracker still holds it")
	}

	// A click pair presses AND releases atomically: nothing stays held.
	h.note(meLeftDown, now)
	h.note(meLeftUp, now)
	if h.left {
		t.Fatalf("click pair must leave nothing held")
	}
}

// A click whose down and up travel as separate wire commands wedges the
// physical mouse forever when the up is lost -- the pair must be built AND
// sent as one atomic unit inside the helper.
func TestBuildClickPairIsAtomicAndCorrect(t *testing.T) {
	m := Monitor{ID: 0, Primary: true, X: 0, Y: 0, Width: 1920, Height: 1080}

	// Coordless: pure down+up at the current cursor position.
	down, up, err := buildClickPair(inputRequest{Action: "click", Button: "left"}, []Monitor{m})
	if err != nil {
		t.Fatalf("coordless click: %v", err)
	}
	if down.DwFlags != meLeftDown || up.DwFlags != meLeftUp {
		t.Fatalf("coordless flags: got %#x/%#x", down.DwFlags, up.DwFlags)
	}
	if down.Dx != 0 || down.Dy != 0 {
		t.Fatalf("coordless click must not move the cursor")
	}

	// With position: aim first (absolute virtual), then press and release.
	down, up, err = buildClickPair(inputRequest{
		Action: "click", Button: "right", HasPos: true,
		MonitorIndex: 0, NX: 0.5, NY: 0.5,
	}, []Monitor{m})
	if err != nil {
		t.Fatalf("positioned click: %v", err)
	}
	wantDown := uint32(meMove | meAbsolute | meVirtual | meRightDown)
	if down.DwFlags != wantDown || up.DwFlags != meRightUp {
		t.Fatalf("positioned flags: got %#x/%#x", down.DwFlags, up.DwFlags)
	}
	// The aim coordinates follow AbsolutePointer's own contract.
	wantX, wantY := AbsolutePointer(Bounds{W: 1920, H: 1080}, Bounds{W: 1920, H: 1080}, 0.5, 0.5)
	if down.Dx != wantX || down.Dy != wantY {
		t.Fatalf("positioned click must aim at the fraction centre, got (%d,%d), want (%d,%d)", down.Dx, down.Dy, wantX, wantY)
	}
	// The release must NOT carry movement flags: it releases wherever the
	// down pressed.
	if up.DwFlags&meMove != 0 {
		t.Fatalf("up must not move the cursor")
	}
}
