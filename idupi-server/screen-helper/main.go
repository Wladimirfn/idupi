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
)

type request struct {
	ID      *int   `json:"id"`
	Cmd     string `json:"cmd"`
	Monitor *int   `json:"monitor"`
	Width   *int   `json:"width"`
	Height  *int   `json:"height"`
	Quality *int   `json:"quality"`
}

type frameMeta struct {
	ID      int    `json:"id"`
	W       int32  `json:"w"`
	H       int32  `json:"h"`
	Bytes   int    `json:"bytes"`
	Quality int    `json:"quality"`
	Monitor string `json:"monitor"`
}

func main() {
	in := bufio.NewScanner(os.Stdin)
	in.Buffer(make([]byte, 64*1024), 1024*1024)
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()

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

	jpegBytes, err := captureJPEG(m, int(tw), int(th), quality)
	if err != nil {
		writeError(out, req.ID, err)
		return
	}

	meta, err := json.Marshal(frameMeta{
		ID: deref(req.ID), W: tw, H: th,
		Bytes: len(jpegBytes), Quality: quality, Monitor: m.Name,
	})
	if err != nil {
		writeError(out, req.ID, err)
		return
	}
	out.Write(EncodeFrame(meta, jpegBytes))
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
