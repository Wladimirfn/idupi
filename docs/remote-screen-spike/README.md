# Remote screen spike — throwaway, kept for its Win32 signatures

Scratch code that proved the approach in `../remote-screen-module.md` is
viable. **Not production code**: no error recovery, no lifecycle, handles
leaked between iterations, one hardcoded monitor.

Read it for the Win32 struct layouts and call sequences (`EnumDisplayMonitors`,
`GetMonitorInfoW`, `CreateDIBSection` with a negative `biHeight`, `BitBlt`),
then write the real helper. Do not extend this file into the real one.

    cd docs/remote-screen-spike && go mod init spike && go build -o spike.exe . && ./spike.exe

Measured on the target machine (two 1920x1080 monitors, the left one at
x=-1920): ~57-59 ms/frame, ~17 fps, ~160 KB/frame at full scale, JPEG q55.
