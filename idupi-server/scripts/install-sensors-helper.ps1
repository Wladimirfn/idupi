#Requires -Version 5.1
<#
.SYNOPSIS
    Installs the elevated IDUPI sensors helper as a logon scheduled task.

.DESCRIPTION
    Copies sensors-helper.ps1 to %ProgramData%\IDUPI and locks that folder down so
    only Administrators and SYSTEM can write to it: the elevated task must never
    execute a file a normal user can modify. It then registers the "IDUPI Sensors"
    task (at logon of the current user, RunLevel Highest, restart on failure, no
    execution time limit), starts it and reads back the first sample.

    Run it from a normal shell: it re-launches itself elevated (UAC prompt).
    Use -Uninstall to stop and remove the task and the ProgramData copy.

.PARAMETER Uninstall
    Removes the scheduled task and the installed helper copy.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1 -Uninstall
#>
param([switch]$Uninstall)

$ErrorActionPreference = "Stop"

$TaskName = "IDUPI Sensors"
$ProgramDataDir = Join-Path $env:ProgramData "IDUPI"
$InstalledScript = Join-Path $ProgramDataDir "sensors-helper.ps1"
$SensorsFile = Join-Path $ProgramDataDir "sensors.json"
$SourceScript = Join-Path $PSScriptRoot "sensors-helper.ps1"

$Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$CurrentUser = $Identity.Name
$IsAdmin = (New-Object System.Security.Principal.WindowsPrincipal($Identity)).IsInRole(
    [System.Security.Principal.WindowsBuiltInRole]::Administrator
)

function Show-SensorReadback {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Output "Todavía no hay lectura de sensores (el servicio puede tardar unos segundos en el primer arranque)."
        return
    }
    try {
        $data = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        Write-Output "El archivo de sensores existe pero no se pudo leer."
        return
    }

    $temperature = $data.cpu.temperatureC
    $power = $data.cpu.powerW
    if ($null -eq $temperature -and $null -eq $power) {
        Write-Output "Sin valores todavía: revisá que la tarea esté corriendo con permisos de administrador."
        return
    }

    $temperatureText = "sin lectura"
    if ($null -ne $temperature) { $temperatureText = "$temperature °C" }
    $powerText = "sin lectura"
    if ($null -ne $power) { $powerText = "$power W" }
    Write-Output ("Lectura actual: CPU {0} · {1}" -f $temperatureText, $powerText)
}

function Show-InstallSummary {
    Write-Output "Servicio de sensores IDUPI instalado."
    Write-Output "  Tarea programada: '$TaskName' (al iniciar sesión, con permisos de administrador)."
    Write-Output "  Script protegido: $InstalledScript (solo Administradores y SYSTEM pueden modificarlo)."
    Show-SensorReadback -Path $SensorsFile
}

if (-not $IsAdmin) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($Uninstall) { $argList += "-Uninstall" }

    $elevated = $null
    try {
        $elevated = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argList -Wait -PassThru
    } catch {
        Write-Output "No se pudo pedir permisos de administrador. Ejecutá este script desde una consola elevada."
        exit 1
    }
    if ($null -eq $elevated -or $elevated.ExitCode -ne 0) {
        $code = 1
        if ($elevated) { $code = $elevated.ExitCode }
        Write-Output "La operación elevada terminó con error (código $code)."
        exit $code
    }

    if ($Uninstall) {
        Write-Output "Servicio de sensores IDUPI desinstalado."
    } else {
        Show-InstallSummary
    }
    exit 0
}

$hasScheduledTasksModule = Get-Command Register-ScheduledTask -ErrorAction SilentlyContinue

if ($Uninstall) {
    $removed = $false
    if ($hasScheduledTasksModule) {
        try {
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
            $removed = $true
        } catch { }
        try {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
            $removed = $true
        } catch { }
    } else {
        & schtasks.exe /Query /TN $TaskName 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            & schtasks.exe /End /TN $TaskName 2>$null | Out-Null
            & schtasks.exe /Delete /TN $TaskName /F 2>$null | Out-Null
            $removed = $true
        }
    }

    if (Test-Path -LiteralPath $InstalledScript) {
        Remove-Item -LiteralPath $InstalledScript -Force
        $removed = $true
    }

    if ($removed) {
        Write-Output "Servicio de sensores IDUPI desinstalado (tarea '$TaskName' y copia en ProgramData)."
    } else {
        Write-Output "El servicio de sensores IDUPI no estaba instalado."
    }
    exit 0
}

if (-not (Test-Path -LiteralPath $SourceScript)) {
    Write-Output "No se encontró sensors-helper.ps1 junto a este instalador ($SourceScript)."
    exit 1
}

if (-not (Test-Path -LiteralPath $ProgramDataDir)) {
    [void][System.IO.Directory]::CreateDirectory($ProgramDataDir)
}

function Resolve-Account {
    param([string]$Sid)
    return (New-Object System.Security.Principal.SecurityIdentifier($Sid)).Translate(
        [System.Security.Principal.NTAccount]
    )
}

function Set-RestrictedAcl {
    param([string]$Path, [bool]$IsDirectory)

    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) {
        [void]$acl.RemoveAccessRuleSpecific($rule)
    }

    $inheritance = "None"
    if ($IsDirectory) { $inheritance = "ContainerInherit, ObjectInherit" }
    $allow = [System.Security.AccessControl.AccessControlType]::Allow

    $rules = @(
        (New-Object System.Security.AccessControl.FileSystemAccessRule(
            (Resolve-Account "S-1-5-32-544"),
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance, "None", $allow)),
        (New-Object System.Security.AccessControl.FileSystemAccessRule(
            (Resolve-Account "S-1-5-18"),
            [System.Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance, "None", $allow)),
        (New-Object System.Security.AccessControl.FileSystemAccessRule(
            (Resolve-Account "S-1-5-32-545"),
            [System.Security.AccessControl.FileSystemRights]::ReadAndExecute,
            $inheritance, "None", $allow))
    )

    foreach ($rule in $rules) {
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

# Stop the previous instance before replacing the script it is running from.
if ($hasScheduledTasksModule) {
    try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
}

# Lock the folder BEFORE copying so the installed file inherits the restricted
# ACL; apply it explicitly to the file too for reinstall over an old copy.
Set-RestrictedAcl -Path $ProgramDataDir -IsDirectory $true
Copy-Item -LiteralPath $SourceScript -Destination $InstalledScript -Force
Set-RestrictedAcl -Path $InstalledScript -IsDirectory $false

$startedAt = Get-Date

if ($hasScheduledTasksModule) {
    $action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument (
        "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$InstalledScript`""
    )
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $CurrentUser
    $principal = New-ScheduledTaskPrincipal -UserId $CurrentUser -LogonType Interactive -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -MultipleInstances IgnoreNew `
        -StartWhenAvailable

    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $action `
        -Trigger $trigger `
        -Principal $principal `
        -Settings $settings `
        -Description "IDUPI sensor helper: CPU temperature and power for the dashboard." `
        -Force | Out-Null

    Start-ScheduledTask -TaskName $TaskName
} else {
    $taskCommand = "powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$InstalledScript`""
    & schtasks.exe /Create /TN $TaskName /SC ONLOGON /RL HIGHEST /RU $CurrentUser /TR $taskCommand /F | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Output "No se pudo registrar la tarea programada '$TaskName'."
        exit 1
    }
    & schtasks.exe /Run /TN $TaskName | Out-Null
}

$freshSample = $false
$deadline = $startedAt.AddSeconds(10)
while ((Get-Date) -lt $deadline) {
    if (Test-Path -LiteralPath $SensorsFile) {
        $lastWrite = (Get-Item -LiteralPath $SensorsFile).LastWriteTime
        if ($lastWrite -ge $startedAt.AddSeconds(-1)) {
            $freshSample = $true
            break
        }
    }
    Start-Sleep -Milliseconds 500
}

Show-InstallSummary
if (-not $freshSample) {
    Write-Output "No apareció una lectura nueva en $SensorsFile después de 10 segundos."
    Write-Output "Revisá el estado con: Get-ScheduledTask -TaskName '$TaskName' | Get-ScheduledTaskInfo"
}
