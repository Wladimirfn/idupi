// Containment guard for project file reads (security fix): every path a
// client asks for must resolve INSIDE its project root -- dot-dot escapes and
// absolute paths that would make join() discard the root are refused, not
// resolved. Existing paths are also canonicalized (symlinks / NTFS junctions)
// so a link inside the project cannot point the read somewhere else.

import { lstatSync, readdirSync, realpathSync, statSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";

const IS_WIN = process.platform === "win32";

function isContained(full, rootAbs) {
    const a = IS_WIN ? full.toLowerCase() : full;
    const r = IS_WIN ? rootAbs.toLowerCase() : rootAbs;
    return a === r || a.startsWith(r.endsWith(sep) ? r : r + sep);
}

function defaultRealpath(p) {
    return realpathSync.native(p);
}

/**
 * Resolves userPath against projectRoot and answers whether it stays inside.
 * Returns { ok: true, path } with the ABSOLUTE resolved path, or { ok: false }
 * when the resolution escapes the root (or IS an absolute path elsewhere), or
 * when the existing target is a link that resolves outside the real root.
 */
export function resolveProjectFilePath(projectRoot, userPath, { realpath = defaultRealpath } = {}) {
    const rootAbs = resolve(projectRoot);
    const full = resolve(rootAbs, userPath);
    if (!isContained(full, rootAbs)) return { ok: false };

    let realRoot;
    try {
        realRoot = realpath(rootAbs);
    } catch {
        // Root does not exist (yet): nothing on disk to link through.
        return { ok: true, path: full };
    }
    try {
        const realFull = realpath(full);
        if (!isContained(realFull, realRoot)) return { ok: false };
    } catch {
        // Target does not exist: the read will 404; nothing to follow.
    }
    return { ok: true, path: full };
}

const EXCLUDED = ["node_modules", ".git", "build", ".gradle", ".idea", "bin", "obj", ".codegraph", ".system_generated"];
const filesTreeCache = new Map();

/**
 * Project explorer tree. Symlinks and junctions are skipped (lstat), never
 * followed: following them leaked the tree of whatever they pointed at and a
 * link back to an ancestor recursed until the stack overflowed.
 */
export function getProjectFilesTree(dirPath, relativeTo = dirPath) {
    const results = [];

    try {
        const stat = statSync(dirPath);
        const cached = filesTreeCache.get(dirPath);
        if (cached && cached.mtime >= stat.mtimeMs) {
            return cached.tree;
        }

        const items = readdirSync(dirPath);
        for (const item of items) {
            if (EXCLUDED.includes(item)) continue;
            const fullPath = join(dirPath, item);
            const relPath = relative(relativeTo, fullPath).replace(/\\/g, "/");
            try {
                const itemStat = lstatSync(fullPath);
                if (itemStat.isSymbolicLink()) continue;
                if (itemStat.isDirectory()) {
                    results.push({
                        name: item,
                        path: relPath,
                        isDirectory: true,
                        children: getProjectFilesTree(fullPath, relativeTo),
                        status: "Synced"
                    });
                } else {
                    results.push({
                        name: item,
                        path: relPath,
                        isDirectory: false,
                        children: [],
                        status: "Synced"
                    });
                }
            } catch (e) {}
        }

        filesTreeCache.set(dirPath, { mtime: stat.mtimeMs, tree: results });
    } catch (e) {
        console.error(`[Explorer Error] No se pudo leer ${dirPath}:`, e.message);
    }

    return results;
}
