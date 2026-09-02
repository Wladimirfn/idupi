//go:build windows

// idupi-screen: long-lived capture helper for the IDUPI remote screen module.
//
// Protocol — one JSON request per stdin line:
//
//	{"id":1,"cmd":"list"}
//	{"id":2,"cmd":"capture","monitor":0,"width":800,"height":450,"quality":55}
//
// Every stdout message is framed as u32be length || kind || body (see
// protocol.go): control JSON under KindControl, JPEG frames under KindFrame.
// Binary framing keeps the hottest path free of base64's 33% overhead.
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"time"
)

type request struct {
	ID      *int   `json:"id"`
	Cmd     string `json:"cmd"`
	Monitor *int   `json:"monitor"`
	Width   *int   `json:"width"`
	Height  *int   `json:"height"`
	Quality *int   `json:"quality"`
	// Remote input commands (hito 6): coordinates are normalised 0..1
	// against the chosen monitor; pixels never cross the wire -- EXCEPT
	// relmove deltas and keyboard scan codes (hito 7), which are relative
	// or symbolic by nature.
	Action       string   `json:"action"`
	Button       string   `json:"button"`
	MonitorIndex int      `json:"monitorIndex"`
	NX           *float64 `json:"x"`
	NY           *float64 `json:"y"`
	DX           *float64 `json:"dx"`
	DY           *float64 `json:"dy"`
	Axis         *string  `json:"axis"` // scroll axis: "" | "v" | "h"
	Delta        *int     `json:"delta"`
	Code         *int     `json:"code"` // keychar: UTF-16 unit | keyvk: Windows VK
}

type tileRef struct {
	I   int `json:"i"`
	Len int `json:"len"`
}

type frameMeta struct {
	ID      int    `json:"id"`
	W       int32  `json:"w"`
	H       int32  `json:"h"`
	Bytes   int    `json:"bytes"`
	Quality int    `json:"quality"`
	Monitor string `json:"monitor"`
	// Tile frames only (hito 8); keyframes OMIT these so the meta stays
	// byte-compatible with what every existing client parses.
	Type  string    `json:"type,omitempty"` // "tiles"; absent = legacy full JPEG
	Tw    int       `json:"tw,omitempty"`
	Th    int       `json:"th,omitempty"`
	Tiles []tileRef `json:"tiles,omitempty"`
}

func main() {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64*1024), 1024*1024)
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()

	// Stuck-button watchdog: a remote press whose release never arrives
	// wedges the user's PHYSICAL mouse OS-wide. Anything held past the
	// limit gets auto-released -- the worst failure this module can cause
	// must heal itself, not wait for a human to reboot.
	go func() {
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for now := range ticker.C {
			if flags, ok := heldButtons.expired(now, holdLimit); ok {
				if err := executeInput(&mouseInput{Type: 0, DwFlags: flags}); err == nil {
					fmt.Fprintln(os.Stderr, "[watchdog] auto-released remotely-stuck mouse buttons")
				}
			}
		}
	}()

	for in.Scan() {
		line := bytes.TrimSpace(in.Bytes())
		if len(line) == 0 {
			continue
		}
		var req request
		if err := json.Unmarshal(line, &req); err != nil {
			writeControl(out, nil, map[string]any{
				"ok": false, "error": "bad request json: " + err.Error(),
			})
			continue
		}
		dispatch(out, &req)
	}
}

func dispatch(out *bufio.Writer, req *request) {
	// One bad input must never kill the capture helper (exit code 2 = the
	// whole screen-stream dies with it). Any panic inside a command is
	// answered as an error and the loop keeps serving the next request.
	defer func() {
		if r := recover(); r != nil {
			writeError(out, req.ID, fmt.Errorf("internal error: %v", r))
		}
	}()
	switch req.Cmd {
	case "list":
		monitors, err := enumerateMonitors()
		if err != nil {
			writeError(out, req.ID, err)
			return
		}
		writeControl(out, req.ID, map[string]any{
			"ok": true, "monitors": monitors,
		})
	case "capture":
		captureCommand(out, req)
	case "input":
		inputCommand(out, req)
	default:
		writeError(out, req.ID, fmt.Errorf("unknown cmd %q", req.Cmd))
	}
}

func captureCommand(out *bufio.Writer, req *request) {
	if req.Monitor == nil || req.Width == nil || req.Height == nil {
		writeError(out, req.ID, fmt.Errorf("capture needs monitor, width and height"))
		return
	}
	quality := 55
	if req.Quality != nil {
		quality = clampInt(*req.Quality, 1, 100)
	}

	monitors, err := enumerateMonitors()
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	idx := clampInt(*req.Monitor, 0, len(monitors)-1)
	m := monitors[idx]

	tw, th := FitWithin(m.Width, m.Height, int32(*req.Width), int32(*req.Height))
	if tw == 0 || th == 0 {
		writeError(out, req.ID, fmt.Errorf("degenerate capture size"))
		return
	}

	img, err := captureRGBA(m, int(tw), int(th))
	if err != nil {
		writeError(out, req.ID, err)
		return
	}

	// Dirty tiles (hito 8): diff against the PREVIOUS capture of this exact
	// signature. Any change of monitor/size/quality misses the cache and
	// comes out as a clean keyframe (brief §4.3).
	cacheKey := fmt.Sprintf("%d:%d:%d:%d", idx, tw, th, quality)
	prevPix := frameCacheStore.lookup(cacheKey)
	if os.Getenv("IDUPI_DEBUG_TILES") == "1" {
		dirtyDbg := DirtyTiles(img.Pix, prevPix, int(tw), int(th), 64)
		fmt.Fprintf(os.Stderr, "[tiles] dirty=%d/%d prevKnown=%v\n", len(dirtyDbg), TileCount(int(tw), int(th), 64), prevPix != nil)
	}
	meta, payload, err := buildTileFrame(deref(req.ID), img, prevPix, 64, quality)
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	meta.Monitor = m.Name
	frameCacheStore.store(cacheKey, append([]byte(nil), img.Pix...))

	metaJSON, err := json.Marshal(meta)
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	out.Write(EncodeFrame(metaJSON, payload))
	out.Flush()
}

func writeControl(out *bufio.Writer, id *int, payload map[string]any) {
	if id != nil {
		payload["id"] = *id
	}
	body, _ := json.Marshal(payload)
	out.Write(EncodeMessage(KindControl, body))
	out.Flush()
}

func writeError(out *bufio.Writer, id *int, err error) {
	writeControl(out, id, map[string]any{"ok": false, "error": err.Error()})
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func deref(p *int) int {
	if p == nil {
		return -1
	}
	return *p
}

// inputCommand validates one mouse event and fires it through SendInput.
func inputCommand(out *bufio.Writer, req *request) {
	monitors, err := enumerateMonitors()
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	// Accept BOTH wire names for the monitor index: older clients send
	// "monitor", newer ones "monitorIndex". Prefer the explicit index.
	monitorIdx := req.MonitorIndex
	if req.Monitor != nil {
		monitorIdx = *req.Monitor
	}
	delta := 0
	if req.Delta != nil {
		delta = *req.Delta
	}
	action := strings.ToLower(req.Action)
	if action == "" {
		writeError(out, req.ID, fmt.Errorf("input needs an action"))
		return
	}
	hasPos := req.NX != nil && req.NY != nil
	if hasPos && action != "scroll" && (*req.NX < 0 || *req.NX > 1 || *req.NY < 0 || *req.NY > 1) {
		writeError(out, req.ID, fmt.Errorf("normalised coordinates must be within 0..1"))
		return
	}
	if action == "click" {
		// Atomic click (stuck-button fix): down+up in ONE helper command so
		// a lost release can never wedge the physical mouse.
		inReq := inputRequest{Action: action, Button: strings.ToLower(req.Button), MonitorIndex: monitorIdx}
		if hasPos {
			inReq.HasPos = true
			inReq.NX, inReq.NY = *req.NX, *req.NY
		}
		down, up, err := buildClickPair(inReq, monitors)
		if err != nil {
			writeError(out, req.ID, err)
			return
		}
		if err := executeClick(down, up); err != nil {
			writeError(out, req.ID, err)
			return
		}
		now := time.Now()
		heldButtons.note(down.DwFlags, now)
		heldButtons.note(up.DwFlags, now)
		writeControl(out, req.ID, map[string]any{"ok": true})
		return
	}
	if action == "relmove" && (req.DX == nil || req.DY == nil) {
		writeError(out, req.ID, fmt.Errorf("relmove needs dx and dy"))
		return
	}
	if action == "keychar" || action == "keyvk" {
		// Keyboard path (hito 7): no coordinates, just the key code.
		if req.Code == nil {
			writeError(out, req.ID, fmt.Errorf("%s needs code", action))
			return
		}
		in, err := buildKeyInput(action, *req.Code)
		if err != nil {
			writeError(out, req.ID, err)
			return
		}
		if err := executeKeyInput(in); err != nil {
			writeError(out, req.ID, err)
			return
		}
		writeControl(out, req.ID, map[string]any{"ok": true})
		return
	}
	if action == "keydown" || action == "keyup" {
		// Half-event path (bug-2 fix, chord, Aug 28): a single down or
		// up stroke WITHOUT the synthesised opposite edge. The caller
		// sequences the half-events in order (down modifier, keychar
		// char, up modifier) so a Ctrl+V actually registers as a chord.
		// Without this, buildKeyInput would still validate the code,
		// but executeKeyInput would inject the matching release and the
		// modifier would never be held across the character press.
		if req.Code == nil {
			writeError(out, req.ID, fmt.Errorf("%s needs code", action))
			return
		}
		in, err := buildKeyInput(action, *req.Code)
		if err != nil {
			writeError(out, req.ID, err)
			return
		}
		if err := executeKeyHalfEvent(in); err != nil {
			writeError(out, req.ID, err)
			return
		}
		writeControl(out, req.ID, map[string]any{"ok": true})
		return
	}
	inReq := inputRequest{
		Action:       action,
		Button:       strings.ToLower(req.Button),
		MonitorIndex: monitorIdx,
		Delta:        delta,
	}
	if req.Axis != nil {
		inReq.Axis = strings.ToLower(*req.Axis)
	}
	if hasPos {
		inReq.HasPos = true
		inReq.NX, inReq.NY = *req.NX, *req.NY
	}
	if req.DX != nil && req.DY != nil {
		inReq.DX, inReq.DY = *req.DX, *req.DY
	}
	in, err := buildMouseInput(inReq, monitors)
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	if err := executeInput(in); err != nil {
		writeError(out, req.ID, err)
		return
	}
	heldButtons.note(in.DwFlags, time.Now())
	writeControl(out, req.ID, map[string]any{"ok": true})
}
