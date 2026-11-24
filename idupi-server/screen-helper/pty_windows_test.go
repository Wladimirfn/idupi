//go:build windows

package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
	"unsafe"
)

// The test binary doubles as the PTY host: re-executing it with this env
// var runs runPty exactly as `idupi-screen pty` would, without a build step.
const ptyHostEnv = "IDUPI_TEST_AS_PTY_HOST"

func TestMain(m *testing.M) {
	if os.Getenv(ptyHostEnv) == "1" {
		os.Exit(runPty(os.Args[1:]))
	}
	os.Exit(m.Run())
}

// The job struct is handed to the kernel as raw memory, so a layout slip
// would silently disable KILL_ON_JOB_CLOSE. 144 is the documented x64 size.
func TestJobStructSize(t *testing.T) {
	if unsafe.Sizeof(uintptr(0)) != 8 {
		t.Skip("layout check is for 64-bit")
	}
	if got := unsafe.Sizeof(jobBasicLimitInformation{}); got != 64 {
		t.Errorf("JOBOBJECT_BASIC_LIMIT_INFORMATION = %d bytes, want 64", got)
	}
	if got := unsafe.Sizeof(jobExtendedLimitInformation{}); got != 144 {
		t.Errorf("JOBOBJECT_EXTENDED_LIMIT_INFORMATION = %d bytes, want 144", got)
	}
	if got := unsafe.Sizeof(startupInfoEx{}); got != 112 {
		t.Errorf("STARTUPINFOEXW = %d bytes, want 112", got)
	}
}

func TestPtyHostRunsCommand(t *testing.T) {
	cmd := exec.Command(os.Args[0], "--cols", "80", "--rows", "24", "--", "cmd.exe", "/c", "echo hello-pty")
	cmd.Env = append(os.Environ(), ptyHostEnv+"=1")
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	// Keep stdin open (a pipe nobody writes to): EOF would make the host
	// treat the parent as gone and kill the child before it prints.
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	defer stdin.Close()
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("host failed: %v\nstderr: %s", err, stderr.String())
		}
	case <-time.After(20 * time.Second):
		cmd.Process.Kill()
		t.Fatalf("host did not exit; stdout=%q stderr=%q", stdout.String(), stderr.String())
	}
	if !strings.Contains(stdout.String(), "hello-pty") {
		t.Errorf("stdout missing output: %q", stdout.String())
	}
	if !strings.Contains(stderr.String(), `{"exit":0}`) {
		t.Errorf("stderr missing exit report: %q", stderr.String())
	}
}

func TestPtyHostReportsStartupError(t *testing.T) {
	// Bounded like the other subprocess tests: a startup deadlock must fail
	// the test instead of hanging CI.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "--", "definitely-not-a-real-binary-xyz")
	cmd.Env = append(os.Environ(), ptyHostEnv+"=1")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	err := cmd.Run()
	exitErr, ok := err.(*exec.ExitError)
	if !ok || exitErr.ExitCode() != 1 {
		t.Fatalf("want exit code 1, got %v", err)
	}
	if !strings.HasPrefix(stderr.String(), `{"error":`) {
		t.Errorf("stderr = %q, want JSON error", stderr.String())
	}
}

func TestPtyHostForwardsInput(t *testing.T) {
	cmd := exec.Command(os.Args[0], "--", "cmd.exe")
	cmd.Env = append(os.Environ(), ptyHostEnv+"=1")
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	send := func(kind byte, body string) {
		hdr := make([]byte, 4)
		binary.BigEndian.PutUint32(hdr, uint32(1+len(body)))
		stdin.Write(append(append(hdr, kind), body...))
	}
	send('R', `{"cols":100,"rows":30}`)
	send('Z', "ignored")
	send('I', "echo typed-via-frame\r")
	send('I', "exit 7\r")
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		cmd.Process.Kill()
		t.Fatalf("host did not exit; stdout=%q stderr=%q", stdout.String(), stderr.String())
	}
	stdin.Close()
	if !strings.Contains(stdout.String(), "typed-via-frame") {
		t.Errorf("stdout missing typed input echo: %q", stdout.String())
	}
	if !strings.Contains(stderr.String(), `{"exit":7}`) {
		t.Errorf("stderr missing exit 7: %q", stderr.String())
	}
}
