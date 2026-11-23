<#
.SYNOPSIS
  Starts the IDUPI server detached from any terminal or agent session.

.DESCRIPTION
  The server must outlive whatever launched it (a Claude Code shell, a terminal
  window, the logon autostart). This script starts `node index.mjs` as a hidden,
  independent process with its output in idupi-server/logs/, and does nothing
  when something is already listening on the port.

  Use -Restart to stop the current listener first (only if it is node).
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
$process = Start-Process -FilePath $node -ArgumentList "index.mjs" `
    -WorkingDirectory $serverDir -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $logDir "server.out.log") `
    -RedirectStandardError (Join-Path $logDir "server.err.log")

for ($i = 0; $i -lt 40 -and -not (Get-Listener); $i++) { Start-Sleep -Milliseconds 250 }
if (Get-Listener) {
    Write-Output "IDUPI iniciado (PID $($process.Id)) en el puerto $Port. Logs: $logDir"
} else {
    Write-Error "IDUPI no quedó escuchando en el puerto $Port. Revisá $logDir\server.err.log"
    exit 1
}
