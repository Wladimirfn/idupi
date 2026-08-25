// Containment guard for project file reads (security fix): every path a
// client asks for must resolve INSIDE its project root -- dot-dot escapes and
// absolute paths that would make join() discard the root are refused, not
// resolved.

import { resolve, sep } from "node:path";

/**
 * Resolves userPath against projectRoot and answers whether it stays inside.
 * Returns { ok: true, path } with the ABSOLUTE resolved path, or { ok: false }
 * when the resolution escapes the root (or IS an absolute path elsewhere).
 */
export function resolveProjectFilePath(projectRoot, userPath) {
    const rootAbs = resolve(projectRoot);
    const full = resolve(rootAbs, userPath);
    const contained =
        full === rootAbs || full.startsWith(rootAbs + sep);
    if (!contained) return { ok: false };
    return { ok: true, path: full };
}
