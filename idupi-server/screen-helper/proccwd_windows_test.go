//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestProcCwdHelper is not a test: it is the child process the real test
// spawns so proc-cwd can be exercised on a live process whose working
// directory the test controls. Running the test binary itself avoids cmd.exe
// quoting entirely and keeps the child 64-bit (the layout proc-cwd reads).
func TestProcCwdHelper(t *testing.T) {
	if os.Getenv("IDUPI_PROCCWD_HELPER") != "1" {
		return
	}
	time.Sleep(60 * time.Second)
}

func TestProcCwdReadsChildDirectoryAndNullsBogusPid(t *testing.T) {
	dir := t.TempDir()

	cmd := exec.Command(os.Args[0], "-test.run=^TestProcCwdHelper$")
	cmd.Env = append(os.Environ(), "IDUPI_PROCCWD_HELPER=1")
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		t.Fatalf("start helper child: %v", err)
	}
	pid := cmd.Process.Pid
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})

	var got string
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cwd, ok := readProcessCwd(uint32(pid)); ok {
			got = cwd
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if got == "" {
		t.Fatalf("proc-cwd found no current directory for pid %d", pid)
	}
	assertSameDir(t, got, dir)

	const bogus = "2147483000"
	var out bytes.Buffer
	if code := runProcCwd([]string{strconv.Itoa(pid), bogus}, &out); code != 0 {
		t.Fatalf("runProcCwd exit = %d, want 0 (stderr: %s)", code, out.String())
	}

	var payload map[string]*string
	if err := json.Unmarshal(bytes.TrimSpace(out.Bytes()), &payload); err != nil {
		t.Fatalf("proc-cwd output is not JSON: %v (%q)", err, out.String())
	}
	if len(payload) != 2 {
		t.Fatalf("proc-cwd answered %d pids, want 2: %q", len(payload), out.String())
	}
	childPath, ok := payload[strconv.Itoa(pid)]
	if !ok || childPath == nil {
		t.Fatalf("proc-cwd lost the live child: %q", out.String())
	}
	assertSameDir(t, *childPath, dir)
	if bogusPath, ok := payload[bogus]; !ok || bogusPath != nil {
		t.Fatalf("bogus pid must answer null, got %v (present=%v)", bogusPath, ok)
	}
}

func TestProcCwdRejectsBadUsage(t *testing.T) {
	tooMany := make([]string, maxProcCwdPids+1)
	for i := range tooMany {
		tooMany[i] = strconv.Itoa(1000 + i)
	}

	cases := []struct {
		name string
		args []string
	}{
		{"no pids", nil},
		{"not a number", []string{"abc"}},
		{"negative", []string{"-5"}},
		{"too many pids", tooMany},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var out bytes.Buffer
			if code := runProcCwd(tc.args, &out); code != 2 {
				t.Fatalf("runProcCwd(%v) exit = %d, want 2", tc.args, code)
			}
			if out.Len() != 0 {
				t.Fatalf("bad usage must not print a payload, got %q", out.String())
			}
		})
	}
}

func TestProcCwdDedupesPids(t *testing.T) {
	pids, err := parseProcCwdPids([]string{"42", "42", " 7 "})
	if err != nil {
		t.Fatalf("parseProcCwdPids: %v", err)
	}
	want := []uint32{42, 7}
	if len(pids) != len(want) || pids[0] != want[0] || pids[1] != want[1] {
		t.Fatalf("parseProcCwdPids = %v, want %v", pids, want)
	}
}

func TestTrimTrailingSeparator(t *testing.T) {
	cases := map[string]string{
		`C:\`:            `C:\`,
		`C:\proj`:        `C:\proj`,
		`C:\proj\`:       `C:\proj`,
		`C:\proj\\`:      `C:\proj`,
		`C:/proj/`:       `C:/proj`,
		`\\srv\share\`:   `\\srv\share`,
		`\\srv\share\a\`: `\\srv\share\a`,
	}
	for input, want := range cases {
		if got := trimTrailingSeparator(input); got != want {
			t.Errorf("trimTrailingSeparator(%q) = %q, want %q", input, got, want)
		}
	}
}

// assertSameDir compares two paths case-insensitively and, when the
// filesystem knows both, by identity: Windows temp directories can reach the
// child through a short 8.3 name even when the test holds the long one.
func assertSameDir(t *testing.T, got, want string) {
	t.Helper()
	gotInfo, gotErr := os.Stat(got)
	wantInfo, wantErr := os.Stat(want)
	if gotErr == nil && wantErr == nil && os.SameFile(gotInfo, wantInfo) {
		return
	}
	if !strings.EqualFold(filepath.Clean(got), filepath.Clean(want)) {
		t.Fatalf("proc cwd = %q, want %q", got, want)
	}
}
