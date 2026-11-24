package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"reflect"
	"strings"
	"testing"
)

func frame(kind byte, body string) []byte {
	out := make([]byte, 4, 5+len(body))
	binary.BigEndian.PutUint32(out, uint32(1+len(body)))
	out = append(out, kind)
	return append(out, body...)
}

func TestReadPtyFrame(t *testing.T) {
	tests := []struct {
		name     string
		in       []byte
		wantKind byte
		wantBody string
		wantErr  string // substring; "" = no error
		wantIs   error  // exact sentinel, when set
	}{
		{name: "input frame", in: frame('I', "ls\r"), wantKind: 'I', wantBody: "ls\r"},
		{name: "resize frame", in: frame('R', `{"cols":100,"rows":40}`), wantKind: 'R', wantBody: `{"cols":100,"rows":40}`},
		{name: "kind only, empty body", in: frame('I', ""), wantKind: 'I', wantBody: ""},
		{name: "unknown kind is returned, caller ignores it", in: frame('Z', "x"), wantKind: 'Z', wantBody: "x"},
		{name: "binary body survives", in: frame('I', "\x00\xff\x1b[A"), wantKind: 'I', wantBody: "\x00\xff\x1b[A"},
		{name: "empty stream is clean EOF", in: nil, wantIs: io.EOF},
		{name: "truncated header", in: []byte{0, 0}, wantIs: io.ErrUnexpectedEOF},
		{name: "zero-length frame", in: []byte{0, 0, 0, 0}, wantErr: "empty pty frame"},
		{name: "oversize frame", in: []byte{0, 0x10, 0, 1}, wantErr: "too large"},
		{name: "huge length is refused before allocating", in: []byte{0xff, 0xff, 0xff, 0xff}, wantErr: "too large"},
		{name: "header only, body missing", in: []byte{0, 0, 0, 5}, wantIs: io.ErrUnexpectedEOF},
		{name: "truncated body", in: append([]byte{0, 0, 0, 5}, 'I', 'a'), wantIs: io.ErrUnexpectedEOF},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			kind, body, err := readPtyFrame(bytes.NewReader(tc.in))
			switch {
			case tc.wantIs != nil:
				if err != tc.wantIs {
					t.Fatalf("err = %v, want %v", err, tc.wantIs)
				}
			case tc.wantErr != "":
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("err = %v, want containing %q", err, tc.wantErr)
				}
			default:
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if kind != tc.wantKind || string(body) != tc.wantBody {
					t.Fatalf("got (%q, %q), want (%q, %q)", kind, body, tc.wantKind, tc.wantBody)
				}
			}
		})
	}
}

func TestReadPtyFrameMaxSizeAccepted(t *testing.T) {
	body := strings.Repeat("a", maxPtyFrame-1) // total = maxPtyFrame exactly
	kind, got, err := readPtyFrame(bytes.NewReader(frame('I', body)))
	if err != nil || kind != 'I' || len(got) != len(body) {
		t.Fatalf("max-size frame rejected: kind=%q len=%d err=%v", kind, len(got), err)
	}
}

func TestReadPtyFrameSequence(t *testing.T) {
	var stream bytes.Buffer
	stream.Write(frame('I', "a"))
	stream.Write(frame('R', `{"cols":1,"rows":1}`))
	stream.Write(frame('I', "b"))
	var kinds []byte
	for {
		kind, _, err := readPtyFrame(&stream)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		kinds = append(kinds, kind)
	}
	if string(kinds) != "IRI" {
		t.Fatalf("kinds = %q, want IRI", kinds)
	}
}

func TestParseResize(t *testing.T) {
	tests := []struct {
		name               string
		body               string
		wantCols, wantRows int
		wantErr            bool
	}{
		{name: "valid", body: `{"cols":100,"rows":40}`, wantCols: 100, wantRows: 40},
		{name: "min bounds", body: `{"cols":1,"rows":1}`, wantCols: 1, wantRows: 1},
		{name: "max bounds", body: `{"cols":1000,"rows":1000}`, wantCols: 1000, wantRows: 1000},
		{name: "invalid json", body: `{cols:`, wantErr: true},
		{name: "not an object", body: `[80,24]`, wantErr: true},
		{name: "empty body", body: ``, wantErr: true},
		{name: "missing rows", body: `{"cols":80}`, wantErr: true},
		{name: "missing cols", body: `{"rows":24}`, wantErr: true},
		{name: "zero cols", body: `{"cols":0,"rows":24}`, wantErr: true},
		{name: "zero rows", body: `{"cols":80,"rows":0}`, wantErr: true},
		{name: "negative", body: `{"cols":-5,"rows":24}`, wantErr: true},
		{name: "too wide", body: `{"cols":1001,"rows":24}`, wantErr: true},
		{name: "too tall", body: `{"cols":80,"rows":1001}`, wantErr: true},
		{name: "string value", body: `{"cols":"80","rows":24}`, wantErr: true},
		{name: "fractional", body: `{"cols":80.5,"rows":24}`, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cols, rows, err := parseResize([]byte(tc.body))
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error, got %dx%d", cols, rows)
				}
				return
			}
			if err != nil || cols != tc.wantCols || rows != tc.wantRows {
				t.Fatalf("got %dx%d err=%v, want %dx%d", cols, rows, err, tc.wantCols, tc.wantRows)
			}
		})
	}
}

func TestParsePtyArgs(t *testing.T) {
	tests := []struct {
		name    string
		args    []string
		want    *ptyArgs
		wantErr bool
	}{
		{
			name: "full",
			args: []string{"--cols", "100", "--rows", "40", "--cwd", `C:\work dir`, "--", "powershell", "-NoLogo"},
			want: &ptyArgs{Cols: 100, Rows: 40, Cwd: `C:\work dir`, Command: []string{"powershell", "-NoLogo"}},
		},
		{
			name: "defaults",
			args: []string{"--", "cmd.exe"},
			want: &ptyArgs{Cols: 120, Rows: 30, Command: []string{"cmd.exe"}},
		},
		{
			name: "equals syntax",
			args: []string{"--cols=80", "--rows=24", "--", "x"},
			want: &ptyArgs{Cols: 80, Rows: 24, Command: []string{"x"}},
		},
		{
			name: "child flags are not ours",
			args: []string{"--cols", "90", "--", "tool", "--cols", "5", "--"},
			want: &ptyArgs{Cols: 90, Rows: 30, Command: []string{"tool", "--cols", "5", "--"}},
		},
		{name: "missing separator", args: []string{"--cols", "80", "cmd"}, wantErr: true},
		{name: "no command", args: []string{"--cols", "80", "--"}, wantErr: true},
		{name: "empty", args: nil, wantErr: true},
		{name: "unknown flag", args: []string{"--bogus", "1", "--", "cmd"}, wantErr: true},
		{name: "stray positional", args: []string{"foo", "--", "cmd"}, wantErr: true},
		{name: "non-numeric cols", args: []string{"--cols", "abc", "--", "cmd"}, wantErr: true},
		{name: "cols too large", args: []string{"--cols", "5000", "--", "cmd"}, wantErr: true},
		{name: "rows zero", args: []string{"--rows", "0", "--", "cmd"}, wantErr: true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parsePtyArgs(tc.args)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("expected error, got %+v", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if !reflect.DeepEqual(got, tc.want) {
				t.Fatalf("got %+v, want %+v", got, tc.want)
			}
		})
	}
}
