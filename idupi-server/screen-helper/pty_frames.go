package main

// Platform-neutral parts of the `pty` mode: the stdin frame parser and the
// command-line parser. They live outside any build tag so they can be unit
// tested without a pseudo console.

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"strconv"
)

const (
	// ptyKindInput carries raw bytes destined for the child's stdin.
	ptyKindInput byte = 'I'
	// ptyKindResize carries {"cols":N,"rows":M}.
	ptyKindResize byte = 'R'

	// maxPtyFrame bounds one frame (kind + body). Input arrives in small
	// keystroke batches; anything near this size is a corrupt or hostile
	// stream, and we refuse before allocating for it.
	maxPtyFrame = 1 << 20

	ptyMinDim     = 1
	ptyMaxDim     = 1000
	ptyDefaultCol = 120
	ptyDefaultRow = 30
)

// readPtyFrame reads one `u32be totalLen || kind || body` frame where
// totalLen = 1 + len(body). A clean end of stream before the first header
// byte returns io.EOF; a stream cut mid-frame returns io.ErrUnexpectedEOF.
func readPtyFrame(r io.Reader) (kind byte, body []byte, err error) {
	var hdr [4]byte
	if _, err = io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, err // io.EOF (clean) or io.ErrUnexpectedEOF (partial header)
	}
	total := binary.BigEndian.Uint32(hdr[:])
	if total == 0 {
		return 0, nil, errors.New("empty pty frame (missing kind byte)")
	}
	if total > maxPtyFrame {
		return 0, nil, fmt.Errorf("pty frame too large: %d bytes (max %d)", total, maxPtyFrame)
	}
	buf := make([]byte, total)
	if _, err = io.ReadFull(r, buf); err != nil {
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		return 0, nil, err
	}
	return buf[0], buf[1:], nil
}

// parseResize decodes a resize frame body and validates the dimensions.
func parseResize(body []byte) (cols, rows int, err error) {
	var req struct {
		Cols *int `json:"cols"`
		Rows *int `json:"rows"`
	}
	if err = json.Unmarshal(body, &req); err != nil {
		return 0, 0, fmt.Errorf("bad resize json: %w", err)
	}
	if req.Cols == nil || req.Rows == nil {
		return 0, 0, errors.New("resize needs cols and rows")
	}
	if err = validateDims(*req.Cols, *req.Rows); err != nil {
		return 0, 0, err
	}
	return *req.Cols, *req.Rows, nil
}

func validateDims(cols, rows int) error {
	if cols < ptyMinDim || cols > ptyMaxDim || rows < ptyMinDim || rows > ptyMaxDim {
		return fmt.Errorf("size %dx%d out of range [%d, %d]", cols, rows, ptyMinDim, ptyMaxDim)
	}
	return nil
}

// ptyArgs is the parsed command line of `idupi-screen pty ...`.
type ptyArgs struct {
	Cols, Rows int
	Cwd        string
	Command    []string
}

// parsePtyArgs parses everything after the `pty` word:
//
//	--cols N --rows M --cwd DIR -- exe [args...]
//
// Flags end at the first literal "--"; everything after it is the child
// command verbatim, so child flags never collide with ours.
func parsePtyArgs(args []string) (*ptyArgs, error) {
	split := -1
	for i, a := range args {
		if a == "--" {
			split = i
			break
		}
	}
	if split < 0 {
		return nil, errors.New("missing -- before the command to run")
	}
	cmd := args[split+1:]
	if len(cmd) == 0 {
		return nil, errors.New("no command given after --")
	}

	fs := flag.NewFlagSet("pty", flag.ContinueOnError)
	fs.SetOutput(io.Discard) // errors are reported as JSON by the caller
	cols := fs.String("cols", strconv.Itoa(ptyDefaultCol), "columns")
	rows := fs.String("rows", strconv.Itoa(ptyDefaultRow), "rows")
	cwd := fs.String("cwd", "", "working directory")
	if err := fs.Parse(args[:split]); err != nil {
		return nil, err
	}
	if fs.NArg() > 0 {
		return nil, fmt.Errorf("unexpected argument %q before --", fs.Arg(0))
	}
	c, err := strconv.Atoi(*cols)
	if err != nil {
		return nil, fmt.Errorf("bad --cols: %w", err)
	}
	r, err := strconv.Atoi(*rows)
	if err != nil {
		return nil, fmt.Errorf("bad --rows: %w", err)
	}
	if err := validateDims(c, r); err != nil {
		return nil, err
	}
	return &ptyArgs{Cols: c, Rows: r, Cwd: *cwd, Command: cmd}, nil
}
