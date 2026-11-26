// Phone command risk classifier (phone-approvals, T1).
//
// Pure on purpose: the same command plus the foreground package always answers
// the same verdict, so the store, the routes and the tests can all depend on
// it without I/O. Reading and navigating the phone stays free; the owner
// approves anything outbound or irreversible.
//
// Whole-word matching is the point: "Enviar" is risky, "Reenviar" is not, and
// "Pagaré mañana" is a plan, not a payment. Diacritics are stripped before
// matching so "enviár" behaves like "enviar".

/** Verbs that turn a tap into an outbound or irreversible action. */
export const RISKY_VERBS = [
    "enviar",
    "send",
    "pagar",
    "pay",
    "eliminar",
    "borrar",
    "delete",
    "aprobar",
    "approve",
    "confirmar",
    "confirm",
    "comprar",
    "buy",
    "transferir",
    "transfer",
    "publicar",
    "post",
    "share",
    "compartir",
];

// Messaging, email, payment and banking apps where a blind tap or a typed
// message is one confirmation away from leaving the phone. Exact package names
// where the app id is stable, plus substring prefixes for the long tail: any
// package containing "bank", "banco", "pay" or "wallet" is treated as money.
export const SENSITIVE_PACKAGE_NAMES = [
    "com.whatsapp",
    "com.whatsapp.w4b",
    "org.telegram.messenger",
    "com.facebook.orca",
    "com.facebook.messenger",
    "com.instagram.android",
    "com.google.android.gm",
    "com.microsoft.office.outlook",
    "com.samsung.android.messaging",
    "com.google.android.apps.messaging",
    "org.thoughtcrime.securesms",
    "com.mercadopago.wallet",
    "com.paypal.android.p2pmobile",
];

export const SENSITIVE_PACKAGE_TOKENS = ["bank", "banco", "pay", "wallet"];

// Chilean banks whose package id does not carry a token above.
export const SENSITIVE_PACKAGE_PREFIXES = [
    "cl.santander",
    "com.santander",
    "cl.bci.",
    "com.bci.",
];

/** Reason codes stored in an approval and printed by the CLI. */
export const RISK_REASONS = {
    typeSubmit: "type_submit",
    riskyText: "risky_text",
    sensitiveApp: "sensitive_app",
    phoneDetected: "phone_detected_risky_target",
};

const RISKY_TEXT_PATTERN = new RegExp(
    `(^|[^\\p{L}\\p{N}])(${RISKY_VERBS.join("|")})(?=$|[^\\p{L}\\p{N}])`,
    "u",
);

/** Lowercase and strip combining marks so accents never hide a verb. */
export function normalizeRiskText(value) {
    if (typeof value !== "string") return "";
    return value
        .normalize("NFD")
        .replace(/\p{M}/gu, "")
        .toLowerCase();
}

/** True when the text is, or contains as a whole word, a risky verb. */
export function isRiskyText(text) {
    const normalized = normalizeRiskText(text);
    if (normalized.length === 0) return false;
    return RISKY_TEXT_PATTERN.test(normalized);
}

/** True for messaging, email, payment and banking packages. */
export function isSensitivePackage(packageName) {
    if (typeof packageName !== "string" || packageName.length === 0) return false;
    const name = packageName.toLowerCase();
    if (SENSITIVE_PACKAGE_NAMES.includes(name)) return true;
    if (SENSITIVE_PACKAGE_TOKENS.some((token) => name.includes(token))) return true;
    return SENSITIVE_PACKAGE_PREFIXES.some((prefix) => name.startsWith(prefix));
}

function targetOf(args) {
    if (typeof args.id === "string" && args.id.length > 0) return args.id;
    if (Number.isInteger(args.x) && Number.isInteger(args.y)) return `${args.x},${args.y}`;
    return null;
}

/**
 * Classifies one already-normalized command.
 *
 * @param {{type?: string, args?: object}} command
 * @param {{foregroundPackage?: string | null}} [context]
 * @returns {{gated: boolean, reason: string, target?: string}}
 */
export function classifyCommand(command, { foregroundPackage = null } = {}) {
    const type = command && typeof command === "object" ? command.type : undefined;
    const args = command && typeof command === "object" && command.args &&
        typeof command.args === "object" && !Array.isArray(command.args)
        ? command.args
        : {};

    if (type === "type") {
        if (args.submit === true) {
            return { gated: true, reason: RISK_REASONS.typeSubmit };
        }
        // Composing text changes nothing until a send: the submit flag above
        // and the phone-side node check own that boundary.
        return { gated: false, reason: "free" };
    }

    if (type === "tap" || type === "long_press") {
        if (typeof args.text === "string" && isRiskyText(args.text)) {
            return { gated: true, reason: RISK_REASONS.riskyText, target: args.text };
        }
        const target = targetOf(args);
        if (target !== null && isSensitivePackage(foregroundPackage)) {
            return { gated: true, reason: RISK_REASONS.sensitiveApp, target };
        }
        return { gated: false, reason: "free" };
    }

    return { gated: false, reason: "free" };
}
