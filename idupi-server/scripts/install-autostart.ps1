<#
.SYNOPSIS
  Starts the IDUPI server automatically when the current user logs on.

.DESCRIPTION
  Creates a shortcut in the user's Startup folder that runs start-server.ps1
  hidden. A Startup shortcut needs no administrator rights (unlike a machine
  scheduled task) and is removed with -Uninstall.
#>
param([switch]$Uninstall)

$ErrorActionPreference = "Stop"
$startup = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startup "IDUPI Server.lnk"

if ($Uninstall) {
    if (Test-Path $shortcutPath) {
        Remove-Item $shortcutPath
        Write-Output "Inicio automático de IDUPI eliminado."
    } else {
        Write-Output "El inicio automático de IDUPI no estaba instalado."
    }
    exit 0
}

$script = Join-Path $PSScriptRoot "start-server.ps1"
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = (Get-Command powershell.exe).Source
$shortcut.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`""
$shortcut.WorkingDirectory = Split-Path -Parent $PSScriptRoot
$shortcut.WindowStyle = 7  # minimized: no console flash at logon
$shortcut.Description = "Starts the IDUPI server at logon"
$shortcut.Save()
Write-Output "IDUPI arrancará solo al iniciar sesión en Windows ($shortcutPath)."
