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
//   - C:\users and /home roots and every user folder directly beneath them
//     (other accounts, Public): protected, deeper subfolders deletable
//   - direct children of home (Desktop, Documents, AndroidStudioProjects...):
//     protected; projects inside them stay deletable
//   - home dot-directories (~/.ssh, ~/.config...) and ~/AppData: protected at
//     any depth
//   - the running server's own trees (repo + cwd): protected together with
//     every ancestor and descendant
//   - relative paths: protected (fail closed)
// Paths are resolved (symlinks/junctions, "..", forward slashes) before any
// comparison so none of those can route around the rules.

import { realpathSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, posix, resolve, win32 } from "node:path";
import { fileURLToPath } from "node:url";

/** Absolute, resolved, lower-cased, without trailing separator; null if relative. */
// UNC shares and device namespaces (\\server\share, \\?\C:\..., //?/C:/...)
// collapse to this marker, which is always protected (fail closed).
const NAMESPACE = "\\\\";
const isNamespace = (s) => /^[\\/]{2}/.test(s);

function canonicalize(p, realpath) {
    let s = String(p);
    if (isNamespace(s)) return NAMESPACE;
    const isWin = /^[a-z]:/i.test(s);
    if (!isWin && !posix.isAbsolute(s)) return null;
    try {
        s = realpath(s);
    } catch {
        // Missing paths cannot be junctions; resolve lexically below.
    }
    // realpath may hand back the long-path form of a drive path.
    const longDrive = /^[\\/]{2}[?.][\\/]([a-z]:.*)$/i.exec(s);
    if (longDrive) s = longDrive[1];
    else if (isNamespace(s)) return NAMESPACE;
    s = isWin || /^[a-z]:/i.test(s) ? win32.resolve(s) : posix.resolve(s);
    return s.replace(/[/\\]+$/, "").toLowerCase();
}

const normalizePath = (p) => String(p).replace(/[/\\]+$/, "").toLowerCase();

const sepOf = (p) => (p.includes("\\") || /^[a-z]:$/.test(p) ? "\\" : "/");
const isInside = (child, parent) => child.startsWith(parent + sepOf(parent));
const isDirectChild = (child, parent) =>
    isInside(child, parent) && !child.slice(parent.length + 1).includes(sepOf(parent));

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

/** Under home, protected at any depth. */
const HOME_DEEP_PROTECTED = ["appdata"];

/**
 * @param {string} homeDir
 * @param {{ protectedTrees?: string[], systemRoots?: string[], realpath?: (p: string) => string }} [options]
 *   systemRoots: extra roots protected at any depth (e.g. %SystemRoot% on D:).
 */
export function makeIsProtectedSystemPath(homeDir, options = {}) {
    const realpath = options.realpath ?? ((p) => p);
    // The home is canonicalized like every target: a junctioned profile
    // (C:\Users\x -> D:\Homes\x) would otherwise never match its own subtree.
    const home = (homeDir && canonicalize(homeDir, realpath)) || normalizePath(homeDir ?? "");
    const canonList = (list) => (list ?? []).filter(Boolean).map((t) => canonicalize(t, realpath)).filter(Boolean);
    const trees = canonList(options.protectedTrees);
    const systemRoots = [...SYSTEM_ROOTS, ...canonList(options.systemRoots)];
    const inOwnHome = (norm) => Boolean(home) && (norm === home || isInside(norm, home));
    return function isProtectedSystemPath(dirPath) {
        if (!dirPath) return true;
        const norm = canonicalize(dirPath, realpath);
        if (norm === null) return true;
        if (/^[a-z]:$/.test(norm) || norm === "" || norm === "/" || norm.startsWith(NAMESPACE)) return true;
        if (norm === home || EXACT_ROOTS.includes(norm)) return true;
        // Any other account's tree (C:\Users\Alice\..., /home/alice/...) at any depth.
        if (EXACT_ROOTS.some((r) => isInside(norm, r)) && !inOwnHome(norm)) return true;
        if (home && isDirectChild(norm, home)) return true;
        if (home && isInside(norm, home)) {
            const first = norm.slice(home.length + 1).split(sepOf(home))[0];
            if (first.startsWith(".") || HOME_DEEP_PROTECTED.includes(first)) return true;
        }
        if (trees.some((t) => norm === t || isInside(norm, t) || isInside(t, norm))) return true;
        return systemRoots.some((r) => norm === r || isInside(norm, r));
    };
}

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

/** The guard instance the server uses: operator's home + the running server's trees. */
export const isProtectedSystemPath = makeIsProtectedSystemPath(homedir(), {
    protectedTrees: [REPO_ROOT, process.cwd()],
    // Windows may live on another drive: protect wherever it actually is.
    systemRoots: [
        process.env.SystemRoot,
        process.env.ProgramFiles,
        process.env["ProgramFiles(x86)"],
        process.env.ProgramData,
    ],
    realpath: (p) => realpathSync.native(p),
});
