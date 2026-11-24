//go:build windows

package main

// `idupi-screen pty ...`: hosts ONE child process inside a Windows
// pseudo console (ConPTY) and bridges it to our own stdio.
//
//	stdin  : frames `u32be len || kind || body` (see pty_frames.go)
//	stdout : raw VT output of the pseudo console, untouched
//	stderr : one JSON line at the very end, {"exit":N} or {"error":"..."}
//
// The child (and everything it spawns) lives in a Job Object flagged
// KILL_ON_JOB_CLOSE, and this process holds the only job handle. So the
// tree dies when we close the job OR when this host dies for any reason --
// including a hard kill from the parent. That is the whole point: killing a
// terminal must never leave an orphaned shell behind.

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var (
	procCreatePseudoConsole  = kernel32.NewProc("CreatePseudoConsole")
	procResizePseudoConsole  = kernel32.NewProc("ResizePseudoConsole")
	procClosePseudoConsole   = kernel32.NewProc("ClosePseudoConsole")
	procInitProcThreadAttrs  = kernel32.NewProc("InitializeProcThreadAttributeList")
	procUpdateProcThreadAttr = kernel32.NewProc("UpdateProcThreadAttribute")
	procDeleteProcThreadAttr = kernel32.NewProc("DeleteProcThreadAttributeList")
	procCreateProcessW       = kernel32.NewProc("CreateProcessW")
	procCreateJobObjectW     = kernel32.NewProc("CreateJobObjectW")
	procSetInformationJob    = kernel32.NewProc("SetInformationJobObject")
	procAssignProcessToJob   = kernel32.NewProc("AssignProcessToJobObject")
	procResumeThread         = kernel32.NewProc("ResumeThread")
)

const (
	procThreadAttributePseudoConsole = 0x00020016
	extendedStartupInfoPresent       = 0x00080000
	createSuspended                  = 0x00000004
	createUnicodeEnvironment         = 0x00000400
	startfUseStdHandles              = 0x00000100

	jobObjectExtendedLimitInformation = 9
	jobObjectLimitKillOnJobClose      = 0x00002000

	// How long we wait for the output pump / ClosePseudoConsole to settle
	// after the child exits before giving up and exiting anyway.
	ptyDrainTimeout = time.Second
	ptyCloseTimeout = 2 * time.Second
)

// STARTUPINFOEXW: the plain STARTUPINFOW followed by the attribute list
// pointer. syscall.StartupInfo already matches STARTUPINFOW's layout.
type startupInfoEx struct {
	syscall.StartupInfo
	AttributeList uintptr
}

// The three structs below must match the 64-bit Win32 layout exactly;
// Go's natural alignment inserts the same padding the C compiler does
// (e.g. after LimitFlags and ActiveProcessLimit).
type jobBasicLimitInformation struct {
	PerProcessUserTimeLimit int64
	PerJobUserTimeLimit     int64
	LimitFlags              uint32
	MinimumWorkingSetSize   uintptr
	MaximumWorkingSetSize   uintptr
	ActiveProcessLimit      uint32
	Affinity                uintptr
	PriorityClass           uint32
	SchedulingClass         uint32
}

type ioCounters struct {
	ReadOperationCount, WriteOperationCount, OtherOperationCount uint64
	ReadTransferCount, WriteTransferCount, OtherTransferCount    uint64
}

type jobExtendedLimitInformation struct {
	BasicLimitInformation jobBasicLimitInformation
	IoInfo                ioCounters
	ProcessMemoryLimit    uintptr
	JobMemoryLimit        uintptr
	PeakProcessMemoryUsed uintptr
	PeakJobMemoryUsed     uintptr
}

// ptyHost owns every handle of one running pseudo console session.
type ptyHost struct {
	mu       sync.Mutex // guards hpc against Resize racing Close
	hpc      uintptr
	hpcShut  bool
	inWrite  syscall.Handle // our end of the child's stdin pipe
	outRead  syscall.Handle // our end of the child's output pipe
	process  syscall.Handle
	job      syscall.Handle
	jobMu    sync.Mutex
	jobShut  bool
	attrKeep []uintptr // attribute list storage; must outlive CreateProcessW
}

// runPty is the entry point for `pty` mode and returns the process exit code.
func runPty(args []string) int {
	a, err := parsePtyArgs(args)
	if err != nil {
		return ptyFail(err)
	}
	if err := procCreatePseudoConsole.Find(); err != nil {
		return ptyFail(errors.New("ConPTY is not available (needs Windows 10 1809 or newer)"))
	}
	h, err := startPtyHost(a)
	if err != nil {
		return ptyFail(err)
	}

	copyDone := make(chan struct{})
	go h.pumpOutput(copyDone)
	go h.pumpInput()

	// Block until the child (not the whole tree) exits.
	syscall.WaitForSingleObject(h.process, syscall.INFINITE)
	var code uint32
	if err := syscall.GetExitCodeProcess(h.process, &code); err != nil {
		return ptyFail(fmt.Errorf("GetExitCodeProcess: %w", err))
	}

	// ClosePseudoConsole can block until its pending output has been read, so
	// it runs while pumpOutput is still draining -- and in its own goroutine
	// so a wedged conhost cannot hang the exit report.
	closed := make(chan struct{})
	go func() {
		h.closePseudoConsole()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(ptyCloseTimeout):
	}
	select {
	case <-copyDone:
	case <-time.After(ptyDrainTimeout):
	}

	writeStderrJSON(map[string]any{"exit": code})
	// Returning exits the process, which closes the job handle and so also
	// reaps any grandchild the shell left behind.
	return 0
}

func ptyFail(err error) int {
	writeStderrJSON(map[string]any{"error": err.Error()})
	return 1
}

func writeStderrJSON(v map[string]any) {
	b, _ := json.Marshal(v)
	os.Stderr.Write(append(b, '\n'))
}

func startPtyHost(a *ptyArgs) (*ptyHost, error) {
	var inRead, inWrite, outRead, outWrite syscall.Handle
	// nil SECURITY_ATTRIBUTES: the handles are non-inheritable, so the child
	// can only reach them through the pseudo console, never directly.
	if err := syscall.CreatePipe(&inRead, &inWrite, nil, 0); err != nil {
		return nil, fmt.Errorf("CreatePipe(input): %w", err)
	}
	if err := syscall.CreatePipe(&outRead, &outWrite, nil, 0); err != nil {
		syscall.CloseHandle(inRead)
		syscall.CloseHandle(inWrite)
		return nil, fmt.Errorf("CreatePipe(output): %w", err)
	}
	closeAll := func() {
		for _, h := range []syscall.Handle{inRead, inWrite, outRead, outWrite} {
			syscall.CloseHandle(h)
		}
	}

	// Our stdio is the pipe to the parent. Make sure conhost and the child can
	// never inherit it (defence in depth; see also STARTF_USESTDHANDLES below).
	for _, std := range []*os.File{os.Stdin, os.Stdout, os.Stderr} {
		syscall.SetHandleInformation(syscall.Handle(std.Fd()), syscall.HANDLE_FLAG_INHERIT, 0)
	}
	// COORD is two int16 passed by value: X (columns) in the low word.
	coord := uintptr(uint16(a.Cols)) | uintptr(uint16(a.Rows))<<16
	var hpc uintptr
	// Flags 0: PSEUDOCONSOLE_INHERIT_CURSOR would make conhost ask us for the
	// cursor position, and nobody on our side is answering.
	hr, _, _ := procCreatePseudoConsole.Call(coord, uintptr(inRead), uintptr(outWrite), 0, uintptr(unsafe.Pointer(&hpc)))
	if hr != 0 {
		closeAll()
		return nil, fmt.Errorf("CreatePseudoConsole failed: HRESULT 0x%08X", uint32(hr))
	}
	h := &ptyHost{hpc: hpc, inWrite: inWrite, outRead: outRead}
	fail := func(err error) (*ptyHost, error) {
		// Kill the (suspended) child and close our pipe ends BEFORE closing the
		// pseudo console: ClosePseudoConsole can block until pending output is
		// read, and with outRead closed there is nothing left for it to wait on.
		if h.process != 0 {
			syscall.TerminateProcess(h.process, 1)
			syscall.CloseHandle(h.process)
		}
		closeAll()
		h.closePseudoConsole()
		h.closeJob()
		return nil, err
	}

	if err := h.createChild(a); err != nil {
		return fail(err)
	}
	// The pseudo console now owns the far ends; keeping our copies open would
	// stop the output pipe from ever reporting EOF.
	syscall.CloseHandle(inRead)
	syscall.CloseHandle(outWrite)
	return h, nil
}

// createChild creates the suspended child bound to the pseudo console,
// puts it into the kill-on-close job, then lets it run. Suspended creation
// closes the window in which it could spawn grandchildren outside the job.
func (h *ptyHost) createChild(a *ptyArgs) error {
	// First call is expected to fail with INSUFFICIENT_BUFFER and report the
	// size we need.
	var size uintptr
	procInitProcThreadAttrs.Call(0, 1, 0, uintptr(unsafe.Pointer(&size)))
	if size == 0 {
		return errors.New("InitializeProcThreadAttributeList: could not size the attribute list")
	}
	// []uintptr: 8-byte aligned and invisible to the GC's pointer scan.
	h.attrKeep = make([]uintptr, (size+7)/8)
	list := uintptr(unsafe.Pointer(&h.attrKeep[0]))
	if r, _, e := procInitProcThreadAttrs.Call(list, 1, 0, uintptr(unsafe.Pointer(&size))); r == 0 {
		return fmt.Errorf("InitializeProcThreadAttributeList: %w", e)
	}
	defer procDeleteProcThreadAttr.Call(list)
	// The attribute VALUE is the HPCON itself, not a pointer to it.
	if r, _, e := procUpdateProcThreadAttr.Call(list, 0, procThreadAttributePseudoConsole, h.hpc, unsafe.Sizeof(h.hpc), 0, 0); r == 0 {
		return fmt.Errorf("UpdateProcThreadAttribute: %w", e)
	}

	var si startupInfoEx
	si.Cb = uint32(unsafe.Sizeof(si))
	si.AttributeList = list
	// STARTF_USESTDHANDLES with all three handles left NULL. Without it the
	// child ends up reading OUR stdin pipe (observed: it swallowed pieces of
	// the frame stream, desyncing the parser and starving the console of
	// input). Setting the flag forces the std handles to come from the
	// pseudo console instead.
	si.Flags = startfUseStdHandles

	parts := make([]string, len(a.Command))
	for i, arg := range a.Command {
		parts[i] = syscall.EscapeArg(arg)
	}
	cmdline, err := syscall.UTF16PtrFromString(strings.Join(parts, " "))
	if err != nil {
		return fmt.Errorf("bad command line: %w", err)
	}
	var cwd *uint16
	if a.Cwd != "" {
		if cwd, err = syscall.UTF16PtrFromString(a.Cwd); err != nil {
			return fmt.Errorf("bad cwd: %w", err)
		}
	}

	var pi syscall.ProcessInformation
	// bInheritHandles=FALSE: stdio comes from the pseudo console attribute.
	r, _, e := procCreateProcessW.Call(
		0, uintptr(unsafe.Pointer(cmdline)), 0, 0, 0,
		extendedStartupInfoPresent|createSuspended|createUnicodeEnvironment,
		0, uintptr(unsafe.Pointer(cwd)),
		uintptr(unsafe.Pointer(&si.StartupInfo)), uintptr(unsafe.Pointer(&pi)),
	)
	runtime.KeepAlive(&si)
	runtime.KeepAlive(h.attrKeep)
	if r == 0 {
		return fmt.Errorf("CreateProcessW: %w", e)
	}
	h.process = pi.Process
	defer syscall.CloseHandle(pi.Thread)

	if err := h.createJob(); err != nil {
		return err
	}
	if r, _, e := procAssignProcessToJob.Call(uintptr(h.job), uintptr(h.process)); r == 0 {
		return fmt.Errorf("AssignProcessToJobObject: %w", e)
	}
	if r, _, e := procResumeThread.Call(uintptr(pi.Thread)); r == ^uintptr(0) { // (DWORD)-1 on failure
		return fmt.Errorf("ResumeThread: %w", e)
	}
	return nil
}

func (h *ptyHost) createJob() error {
	job, _, e := procCreateJobObjectW.Call(0, 0)
	if job == 0 {
		return fmt.Errorf("CreateJobObjectW: %w", e)
	}
	h.job = syscall.Handle(job)
	var info jobExtendedLimitInformation
	info.BasicLimitInformation.LimitFlags = jobObjectLimitKillOnJobClose
	r, _, e := procSetInformationJob.Call(job, jobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), unsafe.Sizeof(info))
	if r == 0 {
		return fmt.Errorf("SetInformationJobObject: %w", e)
	}
	return nil
}

// closeJob closes the job handle, which kills every process still in it.
func (h *ptyHost) closeJob() {
	h.jobMu.Lock()
	defer h.jobMu.Unlock()
	if h.job != 0 && !h.jobShut {
		h.jobShut = true
		syscall.CloseHandle(h.job)
	}
}

func (h *ptyHost) closePseudoConsole() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.hpc != 0 && !h.hpcShut {
		h.hpcShut = true
		procClosePseudoConsole.Call(h.hpc)
	}
}

func (h *ptyHost) resize(cols, rows int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.hpcShut {
		return
	}
	procResizePseudoConsole.Call(h.hpc, uintptr(uint16(cols))|uintptr(uint16(rows))<<16)
}

// terminate kills the whole tree and exits; used when our parent goes away
// or the stream is unusable.
func (h *ptyHost) terminate(code int) {
	h.closeJob()
	os.Exit(code)
}

// pumpOutput forwards pseudo console output to stdout as it arrives. No
// buffering: a TUI redraw split by a flush delay would show up as tearing.
func (h *ptyHost) pumpOutput(done chan<- struct{}) {
	defer close(done)
	buf := make([]byte, 32*1024)
	for {
		// syscall.Read maps ERROR_BROKEN_PIPE (the console closed) to 0, nil.
		n, err := syscall.Read(h.outRead, buf)
		if n > 0 {
			if _, werr := os.Stdout.Write(buf[:n]); werr != nil {
				h.terminate(1) // nobody is reading us any more
			}
		}
		if err != nil || n == 0 {
			return
		}
	}
}

// pumpInput decodes stdin frames until the parent closes the stream.
func (h *ptyHost) pumpInput() {
	in := bufio.NewReader(os.Stdin)
	for {
		kind, body, err := readPtyFrame(in)
		if err != nil {
			if err != io.EOF {
				writeStderrJSON(map[string]any{"error": "stdin: " + err.Error()})
			}
			// Parent gone (EOF) or stream corrupt: drop the tree with us.
			h.terminate(0)
		}
		switch kind {
		case ptyKindInput:
			h.writeInput(body)
		case ptyKindResize:
			if cols, rows, err := parseResize(body); err == nil {
				h.resize(cols, rows)
			}
		}
		// Unknown kinds are ignored so the protocol can grow.
	}
}

func (h *ptyHost) writeInput(b []byte) {
	for len(b) > 0 {
		n, err := syscall.Write(h.inWrite, b)
		if err != nil {
			return // child is gone; the exit path reports it
		}
		b = b[n:]
	}
}
