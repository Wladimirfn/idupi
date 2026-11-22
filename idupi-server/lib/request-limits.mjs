// Request body limits, decided from the headers alone.
//
// Every POST route buffers its body in memory (`body += chunk`) with no cap.
// The limit is enforced up front instead of inside each route: several routes
// attach their 'data' reader only after an await, so a global 'data' listener
// would switch the stream into flowing mode and drop their bytes. Clients
// (the Android app uses Ktor TextContent) always send Content-Length, so a
// chunked body without one is refused rather than counted.

export const MAX_BODY_BYTES = 1024 * 1024;

/**
 * @param {{ headers?: Record<string, string | string[] | undefined> }} req
 * @returns {null | { status: number, error: string }}
 */
export function checkBodyLimits(req, maxBytes = MAX_BODY_BYTES) {
    const headers = req.headers || {};
    const rawLength = headers["content-length"];
    if (rawLength !== undefined) {
        const length = Number(rawLength);
        if (!Number.isFinite(length) || length < 0) {
            return { status: 400, error: "Content-Length inválido" };
        }
        if (length > maxBytes) {
            return { status: 413, error: `El cuerpo supera el límite de ${maxBytes} bytes` };
        }
        return null;
    }
    const encoding = String(headers["transfer-encoding"] || "").toLowerCase();
    if (encoding && encoding !== "identity") {
        return { status: 411, error: "Content-Length es obligatorio" };
    }
    return null;
}
