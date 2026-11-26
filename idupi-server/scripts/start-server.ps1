<#
.SYNOPSIS
  Starts the IDUPI server detached from any terminal or agent session.

.DESCRIPTION
  The server must outlive whatever launched it (a Claude Code shell, a terminal
  window, the logon autostart). This script starts `node index.mjs` as a hidden,
  independent process with its output in idupi-server/logs/, and does nothing
  when something is already listening on the port.

  Use -Restart to stop the current listener first (only if it is node). When
  -Restart runs from inside the server's own process tree (a shared PTY), it
  hands the restart to a detached copy of itself, because the tree kill would
  otherwise kill the caller before the new server starts.
#>
param(
    [int]$Port = 8788,
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$serverDir = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $serverDir "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Get-Listener {
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

# True when this script runs inside the process tree of $AncestorId, e.g. from
# a shared PTY hosted by the server. The tree kill below would then also kill
# this script before it could start the replacement.
function Test-IsDescendantOf([int]$AncestorId) {
    $current = $PID
    for ($depth = 0; $depth -lt 64; $depth++) {
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$current" -ErrorAction SilentlyContinue
        if (-not $proc -or $proc.ParentProcessId -eq 0 -or $proc.ParentProcessId -eq $current) { return $false }
        if ($proc.ParentProcessId -eq $AncestorId) { return $true }
        $current = $proc.ParentProcessId
    }
    return $false
}

# Re-runs this script with -Restart through WMI. The new process is a child of
# WmiPrvSE, outside the server's tree, so it survives the tree kill. Its output
# goes to logs/restart.log because the caller is about to be killed.
function Start-DetachedRestart {
    $restartLog = Join-Path $logDir "restart.log"
    $inner = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Port $Port -Restart"
    $commandLine = "cmd.exe /d /s /c `"$inner >> `"$restartLog`" 2>&1`""
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
    $result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine               = $commandLine
        CurrentDirectory          = $serverDir
        ProcessStartupInformation = $startup
    }
    if ($result.ReturnValue -ne 0) {
        Write-Error "No pude delegar el reinicio (Win32_Process.Create devolvió $($result.ReturnValue))."
        exit 1
    }
}

$listener = Get-Listener
if ($listener) {
    $owner = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
    if (-not $Restart) {
        Write-Output "IDUPI ya está corriendo en el puerto $Port (PID $($listener.OwningProcess))."
        exit 0
    }
    if (-not $owner -or $owner.ProcessName -ne "node") {
        Write-Error "El puerto $Port está ocupado por '$($owner.ProcessName)', no por node. No lo detengo."
        exit 1
    }
    if (Test-IsDescendantOf $listener.OwningProcess) {
        Start-DetachedRestart
        Write-Output "Este terminal corre dentro del servidor y se cerrará con el reinicio. El reinicio sigue por fuera; revisa $logDir\restart.log."
        exit 0
    }
    # Tree kill: PTY hosts and legacy terminals are children of the server.
    & taskkill /PID $listener.OwningProcess /T /F | Out-Null
    for ($i = 0; $i -lt 20 -and (Get-Listener); $i++) { Start-Sleep -Milliseconds 250 }
}

# Keep one previous log for post-mortems.
foreach ($name in "server.out.log", "server.err.log") {
    $path = Join-Path $logDir $name
    if (Test-Path $path) { Move-Item -Force $path "$path.1" }
}

$node = (Get-Command node -ErrorAction Stop).Source
# index.mjs reads PORT from its environment. Start-Process has no per-child
# environment on PowerShell 5.1, so set it on this process just long enough to
# spawn the child and restore it afterwards (the script may be dot-sourced or
# run from a shell that already has PORT set).
$previousPort = $env:PORT
$env:PORT = "$Port"
try {
    $process = Start-Process -FilePath $node -ArgumentList "index.mjs" `
        -WorkingDirectory $serverDir -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "server.out.log") `
        -RedirectStandardError (Join-Path $logDir "server.err.log")
} finally {
    if ($null -eq $previousPort) {
        Remove-Item Env:\PORT -ErrorAction SilentlyContinue
    } else {
        $env:PORT = $previousPort
    }
}

for ($i = 0; $i -lt 40 -and -not (Get-Listener); $i++) { Start-Sleep -Milliseconds 250 }
if (Get-Listener) {
    Write-Output "IDUPI iniciado (PID $($process.Id)) en el puerto $Port. Logs: $logDir"
} else {
    Write-Error "IDUPI no quedó escuchando en el puerto $Port. Revisa $logDir\server.err.log"
    exit 1
}
