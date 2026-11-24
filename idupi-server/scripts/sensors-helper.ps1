#Requires -Version 5.1
<#
.SYNOPSIS
    Samples CPU temperature/power and DIMM temperature through LibreHardwareMonitorLib
    and writes them as JSON for the IDUPI dashboard.

.DESCRIPTION
    Windows exposes no Ryzen CPU temperature or package power through WMI on this PC
    (MSAcpi_ThermalZoneTemperature has 0 instances), so this helper loads the pinned
    LibreHardwareMonitorLib that install-sensors-helper.ps1 downloads into
    %ProgramData%\IDUPI\lhm. LibreHardwareMonitor reads those sensors through the
    PawnIO kernel driver, installed by the same installer. Sensor values read as 0
    without administrator rights; install-sensors-helper.ps1 registers this script
    as a logon task with highest privileges.

    The library is loaded with [Reflection.Assembly]::LoadFrom, not Add-Type: the
    LoadFrom context resolves the dependency DLLs that sit next to it, while Add-Type
    does not. Do not add a scriptblock AssemblyResolve handler: it overflows the stack
    in PowerShell 5.1 with this dependency set.

    Every hardware and subhardware is updated before each sample: motherboard
    SuperIO and DIMM sensors live in SubHardware. Package power is an energy delta,
    so the first elevated sample updates twice (the loop's later samples get it for
    free). The output file is replaced atomically (temp file + Move-Item -Force)
    so the server never reads a partial JSON document. A single failed sensor read
    never ends the loop.

.PARAMETER LibDir
    Folder with LibreHardwareMonitorLib.dll and its dependency DLLs. Non-elevated
    calls default to $env:IDUPI_LHM_LIB_DIR, then %ProgramData%\IDUPI\lhm. Elevated
    calls always use %ProgramData%\IDUPI\lhm: a normal user controls that environment
    variable and an elevated load from a user-writable folder would be a privilege
    escalation.

.PARAMETER OutFile
    JSON destination. Defaults to %ProgramData%\IDUPI\sensors.json.

.PARAMETER IntervalMs
    Delay between samples. Defaults to 2000 ms.

.PARAMETER Once
    Takes one sample, writes it and exits. Used by the install smoke check.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File sensors-helper.ps1 -Once -OutFile C:\Temp\sensors.json
#>
param(
    [string]$LibDir = "",
    [string]$OutFile = "",
    [int]$IntervalMs = 2000,
    [switch]$Once
)

$ErrorActionPreference = "Stop"

$Identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
$Principal = New-Object System.Security.Principal.WindowsPrincipal($Identity)
$Elevated = $Principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)

# Resolved through the shell API, not $env:ProgramData: the environment is
# attacker-controlled input and this helper runs elevated.
$ProgramDataRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData)
if (-not $ProgramDataRoot) { $ProgramDataRoot = "C:\ProgramData" }
$InstalledLibDir = Join-Path $ProgramDataRoot "IDUPI\lhm"

if ($Elevated) {
    # Elevated, user environment variables and arguments are attacker-controlled
    # input: a non-admin process could point the DLL load at its own code and gain
    # admin. Only the admin-owned install folder is trusted then.
    if ($LibDir) {
        Write-Warning "Elevated: ignoring -LibDir '$LibDir'; loading only from '$InstalledLibDir'."
    }
    $LibDir = $InstalledLibDir
} elseif (-not $LibDir) {
    if ($env:IDUPI_LHM_LIB_DIR) {
        $LibDir = $env:IDUPI_LHM_LIB_DIR
    } else {
        $LibDir = $InstalledLibDir
    }
}

if (-not $OutFile) {
    $OutFile = Join-Path $ProgramDataRoot "IDUPI\sensors.json"
}

if ($IntervalMs -lt 500) { $IntervalMs = 500 }

$LhmPath = Join-Path $LibDir "LibreHardwareMonitorLib.dll"
if (-not (Test-Path -LiteralPath $LhmPath)) {
    throw "LibreHardwareMonitorLib.dll not found in '$LibDir'. Run idupi-server\scripts\install-sensors-helper.ps1 or pass -LibDir."
}
[void][Reflection.Assembly]::LoadFrom($LhmPath)

$LibVersion = $null
try {
    $LibVersion = [System.Reflection.AssemblyName]::GetAssemblyName($LhmPath).Version.ToString()
} catch {
    try {
        $LibVersion = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($LhmPath).FileVersion
    } catch {
        $LibVersion = $null
    }
}

function Update-HardwareTree {
    param($Hardware)
    try {
        $Hardware.Update()
    } catch {
        # A single failing hardware (a missing SuperIO, a sleeping GPU) must not
        # skip the rest of the tree.
    }
    foreach ($Sub in $Hardware.SubHardware) {
        Update-HardwareTree -Hardware $Sub
    }
}

function Add-HardwareTree {
    param($Hardware, $List)
    [void]$List.Add($Hardware)
    foreach ($Sub in $Hardware.SubHardware) {
        Add-HardwareTree -Hardware $Sub -List $List
    }
}

function Convert-SensorValue {
    param($Sensor)
    $raw = $Sensor.Value
    if ($null -eq $raw) { return $null }
    $value = [double]$raw
    if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) { return $null }
    # LibreHardwareMonitor reports exactly 0 for a sensor it cannot read (no
    # PawnIO driver, no administrator rights): report null so the dashboard never
    # shows a fake 0 C / 0 W. The server applies the same rule to every source.
    if ($value -eq 0) { return $null }
    return [math]::Round($value, 1)
}

function Get-SensorSnapshot {
    param($Computer, [bool]$IsElevated, $LibVersion)

    foreach ($Hardware in $Computer.Hardware) {
        Update-HardwareTree -Hardware $Hardware
    }

    $AllHardware = New-Object System.Collections.ArrayList
    foreach ($Hardware in $Computer.Hardware) {
        Add-HardwareTree -Hardware $Hardware -List $AllHardware
    }

    $Entries = New-Object System.Collections.ArrayList
    foreach ($Hardware in $AllHardware) {
        foreach ($Sensor in $Hardware.Sensors) {
            $sensorType = [string]$Sensor.SensorType
            if ($sensorType -ne "Temperature" -and $sensorType -ne "Power") { continue }
            [void]$Entries.Add([pscustomobject]@{
                hardware = [string]$Hardware.Name
                type     = $sensorType
                name     = [string]$Sensor.Name
                value    = Convert-SensorValue -Sensor $Sensor
            })
        }
    }

    $CpuHardware = $null
    foreach ($Hardware in $AllHardware) {
        if ([string]$Hardware.HardwareType -eq "Cpu") {
            $CpuHardware = $Hardware
            break
        }
    }

    $CpuName = $null
    $CpuTemperature = $null
    $CpuPower = $null
    if ($CpuHardware) {
        $CpuName = [string]$CpuHardware.Name

        $CpuTemperatures = @($Entries | Where-Object {
            $_.hardware -eq $CpuName -and $_.type -eq "Temperature" -and $null -ne $_.value
        })
        foreach ($Preferred in @("Core (Tctl/Tdie)", "CPU Package", "Core Average")) {
            $Match = $CpuTemperatures | Where-Object { $_.name -eq $Preferred } | Select-Object -First 1
            if ($Match) {
                $CpuTemperature = $Match.value
                break
            }
        }
        if ($null -eq $CpuTemperature -and $CpuTemperatures.Count -gt 0) {
            $CpuTemperature = $CpuTemperatures[0].value
        }

        $CpuPowers = @($Entries | Where-Object {
            $_.hardware -eq $CpuName -and $_.type -eq "Power" -and $null -ne $_.value
        })
        $Match = $CpuPowers | Where-Object { $_.name -eq "Package" } | Select-Object -First 1
        if (-not $Match) {
            $Match = $CpuPowers | Where-Object { $_.name -eq "CPU Package" } | Select-Object -First 1
        }
        if ($Match) { $CpuPower = $Match.value }
    }

    # DIMM/memory module hardware first; some boards expose the DIMM sensor on
    # the motherboard SuperIO with a "DIMM #N" sensor name instead.
    $MemoryTemperature = $null
    foreach ($Entry in $Entries) {
        if ($Entry.type -ne "Temperature" -or $null -eq $Entry.value) { continue }
        if ($Entry.hardware -match "(?i)dimm|memory|ram") {
            $MemoryTemperature = $Entry.value
            break
        }
    }
    if ($null -eq $MemoryTemperature) {
        foreach ($Entry in $Entries) {
            if ($Entry.type -ne "Temperature" -or $null -eq $Entry.value) { continue }
            if ($Entry.name -match "(?i)dimm") {
                $MemoryTemperature = $Entry.value
                break
            }
        }
    }

    $sampledAt = [DateTime]::UtcNow.ToString(
        "yyyy-MM-dd'T'HH:mm:ss.fff'Z'",
        [System.Globalization.CultureInfo]::InvariantCulture
    )

    return [ordered]@{
        sampledAt  = $sampledAt
        elevated   = $IsElevated
        source     = "librehardwaremonitor"
        libVersion = $LibVersion
        cpu        = [ordered]@{
            name         = $CpuName
            temperatureC = $CpuTemperature
            powerW       = $CpuPower
        }
        memory     = [ordered]@{
            temperatureC = $MemoryTemperature
        }
        sensors    = @($Entries.ToArray())
    }
}

function Write-Snapshot {
    param([string]$Path, $Snapshot)

    $directory = [System.IO.Path]::GetDirectoryName($Path)
    if ($directory -and -not [System.IO.Directory]::Exists($directory)) {
        [void][System.IO.Directory]::CreateDirectory($directory)
    }

    $json = ConvertTo-Json -InputObject $Snapshot -Depth 6
    $tempPath = "$Path.tmp.$PID"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    try {
        [System.IO.File]::WriteAllText($tempPath, $json, $utf8NoBom)
        Move-Item -LiteralPath $tempPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        }
    }
}

$Mutex = $null
$OwnsMutex = $false
$AlreadyRunning = $false

try {
    try {
        $Mutex = New-Object System.Threading.Mutex($false, "Global\IDUPI_SensorsHelper")
        $OwnsMutex = $Mutex.WaitOne(0)
    } catch {
        # A non-elevated user can be denied the global namespace. Fall back to a
        # per-session mutex so -Once still works from a normal shell.
        try {
            $Mutex = New-Object System.Threading.Mutex($false, "Local\IDUPI_SensorsHelper")
            $OwnsMutex = $Mutex.WaitOne(0)
        } catch {
            $Mutex = $null
            $OwnsMutex = $false
        }
    }

    if ($Mutex -and -not $OwnsMutex) {
        if ($Once) {
            # A one-shot diagnostic sample may run next to the installed service:
            # it writes its own snapshot and exits, so it would otherwise be
            # impossible to smoke-test a library folder while the task is live.
            Write-Warning "Another IDUPI sensors helper instance is running; taking one sample anyway (-Once)."
            try { $Mutex.Dispose() } catch { }
            $Mutex = $null
            $OwnsMutex = $false
        } else {
            $AlreadyRunning = $true
            Write-Warning "Another IDUPI sensors helper instance is already running."
        }
    }

    if (-not $AlreadyRunning) {
        $Computer = New-Object LibreHardwareMonitor.Hardware.Computer
        $Computer.IsCpuEnabled = $true
        $Computer.IsMemoryEnabled = $true
        $Computer.IsMotherboardEnabled = $true

        try {
            $Computer.Open()
            $FirstSample = $true
            while ($true) {
                try {
                    if ($FirstSample -and $Elevated) {
                        # Energy-based power sensors need two updates; warm the
                        # monitor up so the first written file already has power.
                        foreach ($Hardware in $Computer.Hardware) {
                            Update-HardwareTree -Hardware $Hardware
                        }
                        Start-Sleep -Milliseconds 750
                    }
                    $Snapshot = Get-SensorSnapshot -Computer $Computer -IsElevated $Elevated -LibVersion $LibVersion
                    Write-Snapshot -Path $OutFile -Snapshot $Snapshot
                } catch {
                    Write-Warning ("Sensor sample failed: " + $_.Exception.Message)
                    if ($Once) { throw }
                }

                $FirstSample = $false
                if ($Once) { break }
                Start-Sleep -Milliseconds $IntervalMs
            }
        } finally {
            try { $Computer.Close() } catch { }
        }
    }
} finally {
    if ($OwnsMutex -and $Mutex) {
        try { $Mutex.ReleaseMutex() } catch { }
    }
    if ($Mutex) {
        try { $Mutex.Dispose() } catch { }
    }
}
