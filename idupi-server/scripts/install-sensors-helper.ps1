#Requires -Version 5.1
<#
.SYNOPSIS
    Installs the elevated IDUPI sensors helper together with PawnIO and the
    pinned LibreHardwareMonitor library.

.DESCRIPTION
    Runs, in this order:
      1. PawnIO: the kernel driver LibreHardwareMonitor uses to read CPU sensors.
         Skipped when the PawnIO service already exists. Otherwise the pinned
         installer is downloaded to a fresh temp folder, re-staged in the admin-only
         %ProgramData%\IDUPI\installers folder, and verified there by SHA-256 and
         Authenticode (status Valid + expected signer subject) BEFORE it runs
         elevated; a failed or impossible signature check aborts the install.
      2. LibreHardwareMonitor (version pinned in sensors-deps.json): the zip is
         downloaded, re-staged and verified the same way, and only
         LibreHardwareMonitorLib.dll plus the dependency DLLs listed in the manifest
         are extracted into %ProgramData%\IDUPI\lhm. The installed version is stored
         in version.txt so a reinstall with the same pin skips the download.
      3. sensors-helper.ps1: copied to %ProgramData%\IDUPI and locked down so only
         Administrators and SYSTEM can write to it (the elevated task must never
         execute a file a normal user can modify). The "IDUPI Sensors" task is then
         registered (at logon of the current user, RunLevel Highest, restart on
         failure, no execution time limit), restarted and read back.

    Run it from a normal shell: it re-launches itself elevated (UAC prompt).

    Use -Uninstall to stop and remove the task, the helper copy and the pinned
    library. PawnIO is left installed: it is a shared driver other tools may use.

.PARAMETER Uninstall
    Removes the scheduled task, the installed helper copy and %ProgramData%\IDUPI\lhm.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1 -Uninstall
#>
param([switch]$Uninstall)

$ErrorActionPreference = "Stop"

$TaskName = "IDUPI Sensors"
$PawnIoServiceName = "PawnIO"

$ProgramDataRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
if (-not $ProgramDataRoot) { $ProgramDataRoot = "C:\ProgramData" }
$ProgramDataDir = Join-Path $ProgramDataRoot "IDUPI"
$InstalledScript = Join-Path $ProgramDataDir "sensors-helper.ps1"
$LhmDir = Join-Path $ProgramDataDir "lhm"
$LhmVersionFile = Join-Path $LhmDir "version.txt"
$InstallerStageDir = Join-Path $ProgramDataDir "installers"
$SensorsFile = Join-Path $ProgramDataDir "sensors.json"
$SourceScript = Join-Path $PSScriptRoot "sensors-helper.ps1"
$ManifestPath = Join-Path $PSScriptRoot "sensors-deps.json"

$Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$CurrentUser = $Identity.Name
$IsAdmin = (New-Object System.Security.Principal.WindowsPrincipal($Identity)).IsInRole(
    [System.Security.Principal.WindowsBuiltInRole]::Administrator
)

$hasScheduledTasksModule = Get-Command Register-ScheduledTask -ErrorAction SilentlyContinue

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
        Write-Output "Sin valores todavía: revisa que la tarea esté corriendo con permisos de administrador."
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
    Write-Output "  LibreHardwareMonitor: $LhmDir (versión fija, solo DLL del helper)."
    Show-SensorReadback -Path $SensorsFile
}

function Show-PawnIoUninstallNote {
    Write-Output "PawnIO no se desinstala: es un driver compartido que otras herramientas pueden usar."
    Write-Output "Si quieres quitarlo igual, desinstálalo desde 'Aplicaciones' de Windows."
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
    # The owner can always re-ACL the object, so a file pre-created by a normal
    # user would keep control even after the DACL is replaced. Administrators
    # must own it before the install writes anything under ProgramData.
    $acl.SetOwner((Resolve-Account "S-1-5-32-544"))

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

function New-ScratchDir {
    param([string]$Label)
    $dir = Join-Path ([System.IO.Path]::GetTempPath()) (
        "idupi-" + $Label + "-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
    )
    [void][System.IO.Directory]::CreateDirectory($dir)
    return $dir
}

function Assert-NotReparsePoint {
    param([string]$Path, [string]$Label)

    # A junction or symlink pre-created at one of the install paths would make
    # Set-Acl and every elevated write land wherever the attacker points, so
    # the install refuses to touch it instead of following it.
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return }
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw ("{0} es un punto de reanálisis (junction o symlink): {1}. No se instala nada para no escribir fuera de %ProgramData%\IDUPI." -f $Label, $Path)
    }
}

function Reset-InstallerStageDir {
    # Recreated fresh on every run: a leftover folder can be pre-created (and
    # owned) by a normal user, so the DACL and owner are applied to a directory
    # this elevated run created itself.
    if (Test-Path -LiteralPath $InstallerStageDir) {
        Remove-Item -LiteralPath $InstallerStageDir -Recurse -Force
    }
    [void][System.IO.Directory]::CreateDirectory($InstallerStageDir)
    Set-RestrictedAcl -Path $InstallerStageDir -IsDirectory $true
}

function Assert-FileHash {
    param([string]$Path, [string]$ExpectedSha256, [string]$Label)

    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
        throw ("La verificación SHA-256 de {0} falló. Esperado {1}, obtenido {2}." -f $Label, $ExpectedSha256.ToLowerInvariant(), $actual)
    }
}

function Get-VerifiedDownload {
    param([string]$Url, [string]$Destination, [string]$ExpectedSha256, [string]$Label)

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    Assert-FileHash -Path $Destination -ExpectedSha256 $ExpectedSha256 -Label $Label
}

function Get-TrustedStagedFile {
    param([string]$Source, [string]$FileName)

    # The download folder (the invoking user's %TEMP%) is writable by that user, so
    # the file is re-staged in the admin-only ProgramData folder and verified there
    # again before an elevated process runs or extracts it: no time-of-check /
    # time-of-use window between verification and execution.
    if (-not (Test-Path -LiteralPath $InstallerStageDir)) {
        throw ("No existe la carpeta de staging {0}; se recrea al iniciar la instalación." -f $InstallerStageDir)
    }
    $staged = Join-Path $InstallerStageDir $FileName
    Copy-Item -LiteralPath $Source -Destination $staged -Force
    # Lock the staged copy BEFORE the hash/signature verification and the
    # elevated execution: from here on only Administrators and SYSTEM can
    # replace the bytes that get verified.
    Set-RestrictedAcl -Path $staged -IsDirectory $false
    return $staged
}

function Convert-SubjectKey {
    param([string]$Subject)
    if ($null -eq $Subject) { return "" }
    return (($Subject -replace '\s*,\s*', ",").Trim().ToLowerInvariant())
}

function Assert-PawnIoSignature {
    param([string]$Path, [string]$ExpectedSubject)

    try {
        # PowerShell 5.1 may fail to autoload the security module; load it explicitly.
        Import-Module Microsoft.PowerShell.Security -ErrorAction Stop
    } catch {
        throw ("No se pudo cargar la verificación de firmas (Microsoft.PowerShell.Security): {0}. No se instala PawnIO." -f $_.Exception.Message)
    }

    $signature = $null
    try {
        $signature = Get-AuthenticodeSignature -LiteralPath $Path -ErrorAction Stop
    } catch {
        throw ("No se pudo verificar la firma Authenticode de PawnIO: {0}. No se instala PawnIO." -f $_.Exception.Message)
    }

    if ($null -eq $signature -or [string]$signature.Status -ne "Valid") {
        $status = "desconocido"
        if ($null -ne $signature) { $status = [string]$signature.Status }
        throw ("La firma Authenticode de PawnIO no es válida (estado: {0}). No se instala PawnIO." -f $status)
    }

    $actualSubject = Convert-SubjectKey $signature.SignerCertificate.Subject
    $expectedSubject = Convert-SubjectKey $ExpectedSubject
    if ($actualSubject -ne $expectedSubject) {
        throw ("La firma de PawnIO no corresponde al firmante esperado. Esperado: '{0}'. Obtenido: '{1}'. No se instala PawnIO." -f $ExpectedSubject, $signature.SignerCertificate.Subject)
    }
}

function Test-PawnIoInstalled {
    return $null -ne (Get-Service -Name $PawnIoServiceName -ErrorAction SilentlyContinue)
}

function Install-PawnIo {
    if (Test-PawnIoInstalled) {
        Write-Output "PawnIO: ya está instalado (servicio '$PawnIoServiceName' presente)."
        return
    }

    Write-Output "PawnIO: no está instalado. Descargando el instalador $($Manifest.pawnio.version)..."
    $scratch = New-ScratchDir "pawnio"
    $staged = $null
    try {
        $setup = Join-Path $scratch "PawnIO_setup.exe"
        Get-VerifiedDownload -Url $Manifest.pawnio.url -Destination $setup `
            -ExpectedSha256 $Manifest.pawnio.sha256 -Label "PawnIO $($Manifest.pawnio.version)"
        $staged = Get-TrustedStagedFile -Source $setup -FileName "PawnIO_setup.exe"
        Assert-FileHash -Path $staged -ExpectedSha256 $Manifest.pawnio.sha256 `
            -Label "PawnIO $($Manifest.pawnio.version) (copia en ProgramData)"
        Write-Output "PawnIO: SHA-256 verificado. Verificando la firma Authenticode..."
        Assert-PawnIoSignature -Path $staged -ExpectedSubject $Manifest.pawnio.signerSubject
        Write-Output "PawnIO: firma válida. Instalando el driver en silencio..."

        $process = Start-Process -FilePath $staged -ArgumentList "-install", "-silent" -Wait -PassThru
        if ($null -eq $process -or $process.ExitCode -ne 0) {
            $code = "desconocido"
            if ($null -ne $process) { $code = $process.ExitCode }
            throw ("La instalación de PawnIO terminó con error (código {0})." -f $code)
        }

        $deadline = (Get-Date).AddSeconds(30)
        while ((Get-Date) -lt $deadline) {
            if (Test-PawnIoInstalled) { break }
            Start-Sleep -Milliseconds 500
        }
        if (-not (Test-PawnIoInstalled)) {
            throw "El instalador de PawnIO terminó, pero el servicio '$PawnIoServiceName' no aparece. Revisa el instalador manualmente."
        }
        Write-Output "PawnIO: driver instalado (servicio '$PawnIoServiceName')."
    } finally {
        Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
        if ($staged -and (Test-Path -LiteralPath $staged)) {
            Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-LhmInstalled {
    if (-not (Test-Path -LiteralPath $LhmVersionFile)) { return $false }
    try {
        $installedVersion = (Get-Content -LiteralPath $LhmVersionFile -Raw).Trim()
    } catch {
        return $false
    }
    if ($installedVersion -ne $Manifest.lhm.version) { return $false }
    foreach ($file in $Manifest.lhm.files) {
        if (-not (Test-Path -LiteralPath (Join-Path $LhmDir $file))) { return $false }
    }
    return $true
}

function Install-Lhm {
    if (Test-LhmInstalled) {
        Write-Output "LibreHardwareMonitor $($Manifest.lhm.version): ya instalado en $LhmDir."
        return
    }

    Write-Output "LibreHardwareMonitor $($Manifest.lhm.version): descargando..."
    $scratch = New-ScratchDir "lhm"
    $stagedZip = $null
    try {
        $zip = Join-Path $scratch "LibreHardwareMonitor.zip"
        Get-VerifiedDownload -Url $Manifest.lhm.url -Destination $zip `
            -ExpectedSha256 $Manifest.lhm.sha256 -Label "LibreHardwareMonitor $($Manifest.lhm.version)"
        $stagedZip = Get-TrustedStagedFile -Source $zip -FileName "LibreHardwareMonitor.zip"
        Assert-FileHash -Path $stagedZip -ExpectedSha256 $Manifest.lhm.sha256 `
            -Label "LibreHardwareMonitor $($Manifest.lhm.version) (copia en ProgramData)"
        Write-Output "LibreHardwareMonitor: SHA-256 verificado. Extrayendo solo las DLL del helper..."

        if (-not (Test-Path -LiteralPath $LhmDir)) {
            [void][System.IO.Directory]::CreateDirectory($LhmDir)
        }
        # Replace whatever an older pin left behind so the folder matches the
        # manifest exactly; the task is already stopped, so no DLL is in use.
        Get-ChildItem -LiteralPath $LhmDir -Force | Remove-Item -Force -Recurse
        # Lock the folder BEFORE extracting so the DLLs inherit the restricted ACL.
        Set-RestrictedAcl -Path $LhmDir -IsDirectory $true

        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $wanted = @{}
        foreach ($file in $Manifest.lhm.files) { $wanted[$file.ToLowerInvariant()] = $file }

        $extracted = 0
        $archive = [System.IO.Compression.ZipFile]::OpenRead($stagedZip)
        try {
            foreach ($entry in $archive.Entries) {
                # Root-level entries only: no satellite folders, no GUI exe.
                if ($entry.FullName -ne [System.IO.Path]::GetFileName($entry.FullName)) { continue }
                $key = $entry.FullName.ToLowerInvariant()
                if (-not $wanted.ContainsKey($key)) { continue }
                $target = Join-Path $LhmDir $wanted[$key]
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
                Set-RestrictedAcl -Path $target -IsDirectory $false
                $extracted++
            }
        } finally {
            $archive.Dispose()
        }

        $missing = @($Manifest.lhm.files | Where-Object {
            -not (Test-Path -LiteralPath (Join-Path $LhmDir $_))
        })
        if ($missing.Count -gt 0) {
            throw ("El ZIP de LibreHardwareMonitor no contenía: {0}" -f ($missing -join ", "))
        }

        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($LhmVersionFile, $Manifest.lhm.version, $utf8NoBom)
        Set-RestrictedAcl -Path $LhmVersionFile -IsDirectory $false
        Write-Output ("LibreHardwareMonitor: {0} archivos extraídos en {1}." -f $extracted, $LhmDir)
    } finally {
        Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
        if ($stagedZip -and (Test-Path -LiteralPath $stagedZip)) {
            Remove-Item -LiteralPath $stagedZip -Force -ErrorAction SilentlyContinue
        }
    }
}

function Stop-SensorsTask {
    if ($hasScheduledTasksModule) {
        try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
    } else {
        & schtasks.exe /End /TN $TaskName 2>$null | Out-Null
    }
}

function Start-SensorsTaskIfPresent {
    if ($hasScheduledTasksModule) {
        try { Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch { }
    } else {
        & schtasks.exe /Run /TN $TaskName 2>$null | Out-Null
    }
}

function Wait-SensorsTaskStopped {
    # Stop-ScheduledTask returns before the child process exits; the helper holds
    # LibreHardwareMonitorLib.dll open, so replacing it must wait for the task.
    if (-not $hasScheduledTasksModule) { return }
    $stopDeadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $stopDeadline) {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($null -eq $task -or [string]$task.State -ne "Running") { return }
        Start-Sleep -Milliseconds 250
    }
}

if (-not $IsAdmin) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    if ($Uninstall) { $argList += "-Uninstall" }

    $elevated = $null
    try {
        $elevated = Start-Process -FilePath "powershell.exe" -Verb RunAs -ArgumentList $argList -Wait -PassThru
    } catch {
        Write-Output "No se pudo pedir permisos de administrador. Ejecuta este script desde una consola elevada."
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
        Show-PawnIoUninstallNote
    } else {
        Show-InstallSummary
    }
    exit 0
}

if ($Uninstall) {
    # Uninstall deletes these paths recursively; a pre-created junction would
    # send the deletion into its target. Refuse instead of following it.
    Assert-NotReparsePoint -Path $ProgramDataDir -Label "%ProgramData%\IDUPI"
    Assert-NotReparsePoint -Path $LhmDir -Label "%ProgramData%\IDUPI\lhm"
    Assert-NotReparsePoint -Path $InstallerStageDir -Label "%ProgramData%\IDUPI\installers"
    Assert-NotReparsePoint -Path $InstalledScript -Label "el script instalado (sensors-helper.ps1)"

    $removed = $false
    if ($hasScheduledTasksModule) {
        try {
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction Stop
            $removed = $true
        } catch { }
        Wait-SensorsTaskStopped
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

    if (Test-Path -LiteralPath $LhmDir) {
        Remove-Item -LiteralPath $LhmDir -Recurse -Force
        $removed = $true
    }

    if (Test-Path -LiteralPath $InstallerStageDir) {
        Remove-Item -LiteralPath $InstallerStageDir -Recurse -Force
        $removed = $true
    }

    if ($removed) {
        Write-Output "Servicio de sensores IDUPI desinstalado (tarea '$TaskName', copia en ProgramData y librería LibreHardwareMonitor)."
    } else {
        Write-Output "El servicio de sensores IDUPI no estaba instalado."
    }
    Show-PawnIoUninstallNote
    exit 0
}

if (-not (Test-Path -LiteralPath $SourceScript)) {
    Write-Output "No se encontró sensors-helper.ps1 junto a este instalador ($SourceScript)."
    exit 1
}
if (-not (Test-Path -LiteralPath $ManifestPath)) {
    Write-Output "No se encontró sensors-deps.json junto a este instalador ($ManifestPath)."
    exit 1
}
try {
    $Manifest = Get-Content -LiteralPath $ManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    Write-Output "No se pudo leer sensors-deps.json: $($_.Exception.Message)"
    exit 1
}
if (-not $Manifest.lhm -or -not $Manifest.pawnio -or -not $Manifest.lhm.files) {
    Write-Output "sensors-deps.json no tiene el formato esperado (faltan lhm.files, lhm.sha256 o pawnio.sha256)."
    exit 1
}

# Refuse to touch anything a normal user might have pre-created as a junction or
# symlink: Set-Acl follows reparse points, and so does every elevated write.
Assert-NotReparsePoint -Path $ProgramDataDir -Label "%ProgramData%\IDUPI"
Assert-NotReparsePoint -Path $LhmDir -Label "%ProgramData%\IDUPI\lhm"
Assert-NotReparsePoint -Path $InstallerStageDir -Label "%ProgramData%\IDUPI\installers"
Assert-NotReparsePoint -Path $InstalledScript -Label "el script instalado (sensors-helper.ps1)"

# Stop the running task first: its process holds LibreHardwareMonitorLib.dll open
# and the pinned library may need replacement.
Stop-SensorsTask
Wait-SensorsTaskStopped

# Lock the folder BEFORE anything is written so the installed files inherit the
# restricted ACL; apply it explicitly to each file too for reinstall over an old copy.
if (-not (Test-Path -LiteralPath $ProgramDataDir)) {
    [void][System.IO.Directory]::CreateDirectory($ProgramDataDir)
}
Set-RestrictedAcl -Path $ProgramDataDir -IsDirectory $true
Reset-InstallerStageDir

try {
    Install-PawnIo
    Install-Lhm
} catch {
    Write-Output $_.Exception.Message
    Start-SensorsTaskIfPresent
    exit 1
}

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
    Write-Output "Revisa el estado con: Get-ScheduledTask -TaskName '$TaskName' | Get-ScheduledTaskInfo"
}
