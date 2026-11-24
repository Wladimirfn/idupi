//go:build windows

// proc-cwd subcommand: read each process's live current directory from its
// PEB.
//
// The PTY list needs to know WHERE a terminal is now, not where it was
// launched: a shell that ran `cd C:\proj` and then `opencode` reports the live
// directory here. Same-user processes expose RTL_USER_PROCESS_PARAMETERS
// without elevation; anything WOW64 (different layout), protected or already
// gone answers null for that pid.
//
// x64 layout (the only supported build):
//
//	PEB.ProcessParameters                                 offset 0x20
//	RTL_USER_PROCESS_PARAMETERS.CurrentDirectory.DosPath  offset 0x38
//	UNICODE_STRING { Length u16, MaximumLength u16, pad, Buffer ptr }
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

const (
	maxProcCwdPids = 64
	// 16 Ki UTF-16 units = 32 KiB of raw path bytes, the cap asked for.
	maxProcCwdChars = 16 * 1024

	processQueryLimitedInformation = 0x1000
	processVMRead                  = 0x0010
	processBasicInformationClass   = 0

	pebProcessParametersOffset = 0x20
	currentDirectoryOffset     = 0x38
)

var (
	ntdll = syscall.NewLazyDLL("ntdll.dll")

	procOpenProcess               = kernel32.NewProc("OpenProcess")
	procReadProcessMemory         = kernel32.NewProc("ReadProcessMemory")
	procIsWow64Process            = kernel32.NewProc("IsWow64Process")
	procNtQueryInformationProcess = ntdll.NewProc("NtQueryInformationProcess")

	// uintptr width decides whether the documented x64 offsets apply at all.
	sizeOfPointer = unsafe.Sizeof(uintptr(0))
)

// unicodeString mirrors UNICODE_STRING (Length/MaximumLength bytes, then the
// buffer pointer aligned to 8 on x64).
type unicodeString struct {
	Length        uint16
	MaximumLength uint16
	_             uint32
	Buffer        uintptr
}

// processBasicInformation mirrors PROCESS_BASIC_INFORMATION; the two unnamed
// fields are the padding the native struct carries on x64.
type processBasicInformation struct {
	ExitStatus                   int32
	_                            uint32
	PebBaseAddress               uintptr
	AffinityMask                 uintptr
	BasePriority                 int32
	_                            uint32
	UniqueProcessID              uintptr
	InheritedFromUniqueProcessID uintptr
}

// runProcCwd answers one JSON object on stdout: {"<pid>": "<cwd>" | null}.
// Unreadable pids are null, never an error: the caller shows what it can.
// Exit 2 is reserved for bad usage, so a caller can tell "the helper is being
// called wrong" apart from "that process is gone".
func runProcCwd(args []string, out io.Writer) int {
	pids, err := parseProcCwdPids(args)
	if err != nil {
		fmt.Fprintln(os.Stderr, "idupi-screen proc-cwd:", err)
		return 2
	}

	results := make(map[string]*string, len(pids))
	for _, pid := range pids {
		key := strconv.FormatUint(uint64(pid), 10)
		if cwd, ok := readProcessCwd(pid); ok {
			results[key] = &cwd
		} else {
			results[key] = nil
		}
	}

	body, err := json.Marshal(results)
	if err != nil {
		fmt.Fprintln(os.Stderr, "idupi-screen proc-cwd: encode:", err)
		return 1
	}
	fmt.Fprintln(out, string(body))
	return 0
}

func parseProcCwdPids(args []string) ([]uint32, error) {
	if len(args) == 0 {
		return nil, fmt.Errorf("usage: idupi-screen proc-cwd <pid>...")
	}
	if len(args) > maxProcCwdPids {
		return nil, fmt.Errorf("too many pids: %d (max %d)", len(args), maxProcCwdPids)
	}

	pids := make([]uint32, 0, len(args))
	seen := make(map[uint32]bool, len(args))
	for _, arg := range args {
		value, err := strconv.ParseUint(strings.TrimSpace(arg), 10, 32)
		if err != nil {
			return nil, fmt.Errorf("not a pid: %q", arg)
		}
		pid := uint32(value)
		if seen[pid] {
			continue
		}
		seen[pid] = true
		pids = append(pids, pid)
	}
	return pids, nil
}

// readProcessCwd returns the process's CurrentDirectory. Every failure path is
// "unknown" (ok=false), never an error: a null in the JSON is the contract.
func readProcessCwd(pid uint32) (string, bool) {
	if sizeOfPointer != 8 {
		return "", false
	}

	handle, _, _ := procOpenProcess.Call(
		uintptr(processQueryLimitedInformation|processVMRead),
		0,
		uintptr(pid),
	)
	if handle == 0 {
		return "", false
	}
	defer syscall.CloseHandle(syscall.Handle(handle))

	var wow64 int32
	if r, _, _ := procIsWow64Process.Call(handle, uintptr(unsafe.Pointer(&wow64))); r == 0 || wow64 != 0 {
		// A 32-bit process under WOW64 has a different PEB layout; a 64-bit
		// reader must not guess at it.
		return "", false
	}

	var pbi processBasicInformation
	var returned uint32
	status, _, _ := procNtQueryInformationProcess.Call(
		handle,
		uintptr(processBasicInformationClass),
		uintptr(unsafe.Pointer(&pbi)),
		unsafe.Sizeof(pbi),
		uintptr(unsafe.Pointer(&returned)),
	)
	if status != 0 || pbi.PebBaseAddress == 0 {
		return "", false
	}

	var params uintptr
	if !readRemote(handle, pbi.PebBaseAddress+pebProcessParametersOffset, unsafe.Pointer(&params), unsafe.Sizeof(params)) || params == 0 {
		return "", false
	}

	var dosPath unicodeString
	if !readRemote(handle, params+currentDirectoryOffset, unsafe.Pointer(&dosPath), unsafe.Sizeof(dosPath)) {
		return "", false
	}
	if dosPath.Buffer == 0 || dosPath.Length < 2 {
		return "", false
	}

	bytes := uintptr(dosPath.Length)
	if limit := uintptr(maxProcCwdChars * 2); bytes > limit {
		bytes = limit
	}
	buf := make([]uint16, bytes/2)
	if !readRemote(handle, dosPath.Buffer, unsafe.Pointer(&buf[0]), bytes) {
		return "", false
	}

	path := syscall.UTF16ToString(buf)
	if path == "" {
		return "", false
	}
	return trimTrailingSeparator(path), true
}

func readRemote(handle, address uintptr, dest unsafe.Pointer, size uintptr) bool {
	if size == 0 {
		return false
	}
	var read uintptr
	r, _, _ := procReadProcessMemory.Call(
		handle,
		address,
		uintptr(dest),
		size,
		uintptr(unsafe.Pointer(&read)),
	)
	return r != 0 && read == size
}

// trimTrailingSeparator drops the trailing separator the PEB keeps on
// directories ("C:\proj\"), except on a drive root ("C:\") or a bare UNC
// share, where it is part of the path.
func trimTrailingSeparator(path string) string {
	for len(path) > 3 && (path[len(path)-1] == '\\' || path[len(path)-1] == '/') {
		path = path[:len(path)-1]
	}
	return path
}
