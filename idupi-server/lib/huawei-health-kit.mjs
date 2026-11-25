// Huawei Health Kit connector groundwork (phone-health, T4).
//
// Huawei Health does not publish to Health Connect; its official route is
// Health Kit (Health Service Kit), which needs a HUAWEI Developer account,
// app review and OAuth 2.0 with the owner's HUAWEI ID. The registration steps
// live in docs/huawei-health-kit.md and are done by the owner, later.
//
// This module is the disabled connector shape only:
//
//   - data/huawei-health-kit.json holds the non-secret configuration
//     (clientId, redirectUri, scopes, enabled). It is gitignored like the rest
//     of data/ and must never carry the client secret: that one is read from
//     the IDUPI_HUAWEI_CLIENT_SECRET environment variable.
//   - huaweiHealthKitStatus() reports { enabled, configured, reason } so
//     GET /api/v1/health/sources can list the source as a future one.
//   - no network call, no token request, no OAuth flow runs here.
//
// The scope strings in the JSON are placeholders: the exact strings must be
// copied from the official scope reference at registration time (the guide
// says so). Nothing here validates them against Huawei.

import { readFileSync } from "node:fs";
import { join } from "node:path";

export const HUAWEI_KIT_FILE = "huawei-health-kit.json";
export const HUAWEI_KIT_SOURCE_ID = "huawei_health_kit";
export const HUAWEI_KIT_SOURCE_LABEL = "Huawei Health Kit";
export const HUAWEI_CLIENT_SECRET_ENV = "IDUPI_HUAWEI_CLIENT_SECRET";

/** Config shape documented in docs/huawei-health-kit.md. */
export const HUAWEI_KIT_DEFAULT_CONFIG = {
    enabled: false,
    clientId: "",
    redirectUri: "",
    scopes: [],
    clientSecretEnv: HUAWEI_CLIENT_SECRET_ENV,
};

function readConfig(dataDir, log) {
    try {
        const parsed = JSON.parse(readFileSync(join(dataDir, HUAWEI_KIT_FILE), "utf8"));
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
    } catch (err) {
        if (err && err.code !== "ENOENT") {
            log?.warn?.(`[health] cannot read ${HUAWEI_KIT_FILE}: ${err.message}`);
        }
        return {};
    }
}

function present(value) {
    return typeof value === "string" && value.trim().length > 0;
}

/**
 * Status of the disabled connector. Never throws: a missing or corrupt config
 * file reads as "not enabled", which is the truth before registration.
 *
 * @param {{ dataDir?: string, env?: Record<string, string | undefined>, log?: { warn?: Function } }} [options]
 * @returns {{ enabled: boolean, configured: boolean, reason: string }}
 */
export function huaweiHealthKitStatus({ dataDir = "", env = process.env, log } = {}) {
    const config = dataDir ? readConfig(dataDir, log) : {};
    const enabled = config.enabled === true;

    const clientId = present(config.clientId);
    const redirectUri = present(config.redirectUri);
    const scopes = Array.isArray(config.scopes)
        ? config.scopes.filter((entry) => present(entry))
        : [];
    const secretEnv = present(config.clientSecretEnv)
        ? config.clientSecretEnv.trim()
        : HUAWEI_CLIENT_SECRET_ENV;
    const hasSecret = present(env?.[secretEnv]);

    const missing = [];
    if (!clientId) missing.push("clientId");
    if (!redirectUri) missing.push("redirectUri");
    if (scopes.length === 0) missing.push("scopes");
    if (!hasSecret) missing.push(secretEnv);

    const configured = missing.length === 0;
    let reason;
    if (!enabled && !configured) reason = "disabled";
    else if (!enabled) reason = "registered_but_disabled";
    else if (!configured) reason = `missing_config:${missing.join(",")}`;
    else reason = "ready";

    return { enabled, configured, reason };
}

/**
 * `GET /api/v1/health/sources` entry for the future Huawei Health Kit source.
 * It is listed even while disabled so the owner sees the planned route, and it
 * never pretends to have captured anything.
 */
export function futureHealthSources({ dataDir = "", env = process.env, log } = {}) {
    const status = huaweiHealthKitStatus({ dataDir, env, log });
    return [{
        id: HUAWEI_KIT_SOURCE_ID,
        label: HUAWEI_KIT_SOURCE_LABEL,
        enabled: status.enabled,
        configured: status.configured,
        reason: status.reason,
        lastCaptureAt: null,
        lastDate: null,
    }];
}
