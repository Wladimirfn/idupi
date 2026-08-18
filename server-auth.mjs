// ============================================================================
// IDUPI shared HTTP auth guard for the local bridge servers.
//
// Both servers can spawn shells on this machine, so every request must present
// a valid bearer token. The token is read from the IDUPI_TOKEN environment
// variable, or from a generated .idupi-token file next to this module.
// ============================================================================

import crypto from "node:crypto";
import { readFileSync, writeFileSync, existsSync, chmodSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = dirname(fileURLToPath(import.meta.url));
const TOKEN_FILE = join(REPO_ROOT, ".idupi-token");

/**
 * Returns the token every request must present. Generates and persists a new
 * one the first time the server runs so the process never starts unprotected.
 */
/**
 * Prints the token on every start, not only when it is generated. Otherwise a
 * restart leaves the operator scrolling back through old output to find it,
 * which is how a truncated value gets pasted into the app.
 */
function announceToken(token, origin) {
    console.log("=================================================");
    console.log(`🔑 Token de acceso (${origin}):`);
    console.log("");
    console.log("   " + token);
    console.log("");
    console.log(`   ${token.length} caracteres. Copialo COMPLETO.`);
    console.log("   Pegalo en IDUPI Android → Conexión → Token.");
    console.log("=================================================");
}

export function loadToken() {
    const fromEnv = (process.env.IDUPI_TOKEN || "").trim();
    if (fromEnv) {
        announceToken(fromEnv, "variable de entorno IDUPI_TOKEN");
        return fromEnv;
    }

    if (existsSync(TOKEN_FILE)) {
        const fromFile = readFileSync(TOKEN_FILE, "utf8").trim();
        if (fromFile) {
            announceToken(fromFile, TOKEN_FILE);
            return fromFile;
        }
    }

    const generated = crypto.randomBytes(32).toString("hex");
    writeFileSync(TOKEN_FILE, generated + "\n", { mode: 0o600 });
    // Best effort: POSIX permissions are a no-op on Windows.
    try { chmodSync(TOKEN_FILE, 0o600); } catch { /* ignored */ }

    announceToken(generated, "nuevo, guardado en " + TOKEN_FILE);
    return generated;
}

/**
 * Compares two secrets without leaking their length or contents through
 * timing. Hashing first keeps both operands the same size, which
 * timingSafeEqual requires.
 */
function secretsMatch(presented, expected) {
    const a = crypto.createHash("sha256").update(presented).digest();
    const b = crypto.createHash("sha256").update(expected).digest();
    return crypto.timingSafeEqual(a, b);
}

/**
 * Points at characters that do not belong in the token. Invisible ones (zero
 * width space, word joiner, BOM) survive trim() on both sides and are the usual
 * reason a token that "looks identical" does not match.
 */
function describeIntruders(token) {
    const bad = [];
    for (let i = 0; i < token.length; i++) {
        const ch = token[i];
        if (!/[0-9a-fA-F]/.test(ch)) {
            const code = token.codePointAt(i).toString(16).toUpperCase().padStart(4, "0");
            bad.push(`pos ${i}: U+${code}`);
        }
    }
    if (bad.length === 0) return "";
    return `\n         ↳ caracteres extraños en el token recibido → ${bad.join(", ")}`;
}

function bearerToken(req) {
    const header = req.headers["authorization"] || "";
    const match = /^Bearer\s+(.+)$/i.exec(header.trim());
    return match ? match[1].trim() : "";
}

/**
 * Builds a guard to call as the first statement of an http request handler.
 * Returns true when the request is authenticated and routing should continue;
 * returns false after having already written the rejection response.
 */
export function createAuthGuard(expectedToken) {
    // The Android client is not a browser and needs no CORS headers. Set
    // IDUPI_CORS_ORIGIN to a single explicit origin only for a trusted local
    // web client — never to "*", which would let any visited page reach these
    // endpoints from the user's browser.
    const allowedOrigin = (process.env.IDUPI_CORS_ORIGIN || "").trim();

    return function requireAuth(req, res) {
        if (allowedOrigin) {
            res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
            res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
            res.setHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            res.setHeader("Vary", "Origin");
        }

        if (req.method === "OPTIONS") {
            res.writeHead(allowedOrigin ? 204 : 405);
            res.end();
            return false;
        }

        const presented = bearerToken(req);
        if (!presented || !secretsMatch(presented, expectedToken)) {
            // Say WHY it failed. "Rejected" alone cannot distinguish a missing
            // header from a wrong value, which are very different bugs.
            const reason = !req.headers["authorization"]
                ? "sin header Authorization"
                : !presented
                    ? `header presente pero no es 'Bearer <token>': "${String(req.headers["authorization"]).slice(0, 24)}..."`
                    : `token no coincide (recibido: ${presented.length} chars "${presented.slice(0, 6)}…", esperado: ${expectedToken.length} chars "${expectedToken.slice(0, 6)}…")${describeIntruders(presented)}`;
            console.warn(`[auth] Rechazado ${req.method} ${req.url} desde ${req.socket.remoteAddress} — ${reason}`);
            res.writeHead(401, {
                "Content-Type": "application/json",
                "WWW-Authenticate": "Bearer"
            });
            res.end(JSON.stringify({ error: "No autorizado" }));
            return false;
        }

        return true;
    };
}
