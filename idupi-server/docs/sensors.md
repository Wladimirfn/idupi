# Sensor helper: CPU temperature and power

The dashboard can show CPU temperature, CPU package power and DIMM temperature only
through an elevated process: Windows does not expose the Ryzen sensors to a normal
user (`MSAcpi_ThermalZoneTemperature` has zero instances on the owner's 5600X).

`scripts/sensors-helper.ps1` loads LibreHardwareMonitorLib from
`%ProgramData%\IDUPI\lhm`, reads every hardware and subhardware, and writes
`%ProgramData%\IDUPI\sensors.json` every two seconds. The server
(`lib/system-metrics.mjs`) prefers a fresh helper file over the optional
LibreHardwareMonitor web server and renders `null` as "no disponible". Exactly-zero
temperature/power readings are treated as unreadable, because LibreHardwareMonitor
returns 0 when it cannot reach the hardware (no PawnIO driver or no administrator
rights).

## Install

From a normal shell (the script re-launches itself elevated with a UAC prompt):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1
```

The installer:

1. Installs PawnIO when the `PawnIO` service is missing, after verifying the pinned
   installer's SHA-256 and Authenticode signature (status `Valid` and the expected
   signer subject). If the signature cannot be verified, it installs nothing.
2. Downloads the pinned LibreHardwareMonitor zip, verifies its SHA-256, and extracts
   only the helper DLLs into `%ProgramData%\IDUPI\lhm`. `version.txt` there records
   the installed version, so a reinstall with the same pin skips the download.
3. Copies `sensors-helper.ps1` to `%ProgramData%\IDUPI` (Administrators/SYSTEM full
   control, Users read-only) and registers the `IDUPI Sensors` logon task with
   highest privileges.

Everything downloaded lives outside the repository; recomputing the hashes is how
you check it (see "Bumping versions").

## Uninstall

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File idupi-server\scripts\install-sensors-helper.ps1 -Uninstall
```

This removes the scheduled task, the helper copy, `%ProgramData%\IDUPI\lhm`
and the download staging folder. PawnIO is intentionally left installed: it is a
shared kernel driver other tools may use. Remove it from Windows "Apps" (PawnIO) if
you want it gone.

## Downloaded artifacts

Pinned in [`scripts/sensors-deps.json`](../scripts/sensors-deps.json):

| Artifact | Version | SHA-256 |
|----------|---------|---------|
| [LibreHardwareMonitor.zip](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor/releases/download/v0.9.6/LibreHardwareMonitor.zip) | 0.9.6 | `086d9f1b5a99e643edc2cfaaac16051685b551e4c5ac0b32a57c58c0e529c001` |
| [PawnIO_setup.exe](https://github.com/namazso/PawnIO.Setup/releases/download/2.2.0/PawnIO_setup.exe) | 2.2.0 | `1f519a22e47187f70a1379a48ca604981c4fcf694f4e65b734aaa74a9fba3032` |

PawnIO signer subject (`Get-AuthenticodeSignature`):
`E=admin@namazso.eu, CN=namazso.eu, O=namazso, L=Debrecen, C=HU`.

The installer extracts only these files from the LibreHardwareMonitor zip (the
closure of `LibreHardwareMonitorLib.dll` references, verified to load and enumerate
hardware with `[Reflection.Assembly]::LoadFrom` in PowerShell 5.1):

- `LibreHardwareMonitorLib.dll`
- `BlackSharp.Core.dll`
- `DiskInfoToolkit.dll`
- `HidSharp.dll`
- `RAMSPDToolkit-NDD.dll`
- `System.Buffers.dll`
- `System.Memory.dll`
- `System.Numerics.Vectors.dll`
- `System.Runtime.CompilerServices.Unsafe.dll`

The GUI executable, the plotting/task-scheduler libraries, PDB/XML files and the
satellite resource folders are not extracted.

## Licenses

- LibreHardwareMonitor: MPL-2.0, downloaded from its upstream GitHub releases.
- PawnIO: by namazso, downloaded from its upstream GitHub releases.

Nothing is redistributed in this repository: both artifacts are downloaded from
their publishers at install time and verified against the pinned hashes. Each one is
downloaded to a fresh temp folder, re-staged in the admin-only
`%ProgramData%\IDUPI\installers` folder and verified again there before an elevated
process runs or extracts it, closing the time-of-check/time-of-use window on the
user-writable download folder.

## Bumping versions

1. Download the new release, verify its upstream signature/hash and compute the
   SHA-256.
2. Update `version`, `url` and `sha256` in `sensors-deps.json`; for
   LibreHardwareMonitor also refresh `files` if its dependency closure changed (load
   `LibreHardwareMonitorLib.dll` with `[Reflection.Assembly]::LoadFrom` in a scratch
   folder and walk `GetReferencedAssemblies()`).
3. Re-run the installer. A different `lhm.version` makes it replace
   `%ProgramData%\IDUPI\lhm` and re-download; the PawnIO step is skipped while the
   `PawnIO` service exists.
