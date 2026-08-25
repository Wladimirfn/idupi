// System-path guard for destructive operations (project removal rmSync).
//
// Rules, tuned so real user projects stay deletable while system territory
// never is:
//   - drive roots and empty paths: protected
//   - the home directory ITSELF: protected (its subfolders are not -- that is
//     where projects live)
//   - system roots (C:\Windows, /usr/local, /opt, ...): protected at ANY
//     depth -- the old shallow-depth check made C:\Windows\System32\config
//     deletable
//   - C:\users and /home roots themselves: protected, subfolders deletable

import { homedir } from "node:os";

const normalizePath = (p) => String(p).replace(/[/\\]+$/, "").toLowerCase();

/** Protected at any depth beneath them. */
const SYSTEM_ROOTS = [
    "c:\\windows",
    "c:\\program files",
    "c:\\program files (x86)",
    "c:\\program files\\common files",
    "c:\\programdata",
    "/usr",
    "/usr/local",
    "/etc",
    "/var",
    "/opt",
    "/boot",
    "/srv",
    "/bin",
    "/sbin",
    "/lib",
    "/lib64",
    "/dev",
    "/sys",
    "/proc",
    "/run",
];

/** Protected as an exact match only -- their subfolders are user land. */
const EXACT_ROOTS = ["c:\\users", "/home"];

export function makeIsProtectedSystemPath(homeDir) {
    const home = normalizePath(homeDir ?? "");
    return function isProtectedSystemPath(dirPath) {
        if (!dirPath) return true;
        const norm = normalizePath(dirPath);
        if (/^[a-z]:[/\\]?$/.test(norm) || norm === "" || norm === "/") return true;
        if (norm === home || EXACT_ROOTS.some((r) => norm === r)) return true;
        return SYSTEM_ROOTS.some(
            (r) => norm === r || norm.startsWith(r + "\\") || norm.startsWith(r + "/"),
        );
    };
}

/** The guard instance the server uses, seeded with the operator's home. */
export const isProtectedSystemPath = makeIsProtectedSystemPath(homedir());
