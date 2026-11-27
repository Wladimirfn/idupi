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

/** Verbs that turn a tap into an outbound or irreversible action, by kind. */
export const RISKY_VERB_CATEGORIES = {
    // Outbound text to another person: a bubble request already approves these.
    message: ["enviar", "send", "compartir", "share", "publicar", "post"],
    // Money leaves the owner's account or changes a payment.
    money: ["pagar", "pay", "comprar", "buy", "transferir", "transfer"],
    // Removes data or content for good. Uninstalling an app (phone-dashboard
    // T3) is irreversible too: without these words the agent could uninstall
    // an app without the owner's approval.
    destructive: ["eliminar", "borrar", "delete", "desinstalar", "desinstala", "uninstall"],
    // Confirms something the owner did not spell out; kept gated to be safe.
    confirmation: ["aprobar", "approve", "confirmar", "confirm"],
};

/** Every risky verb, flattened; kept for callers that only need the set. */
export const RISKY_VERBS = Object.values(RISKY_VERB_CATEGORIES).flat();

// Messaging, email, payment and banking apps where a blind tap or a typed
// message is one confirmation away from leaving the phone. Exact package names
// where the app id is stable, plus substring prefixes for the long tail: any
// package containing "bank", "banco", "pay" or "wallet" is treated as money.
export const MONEY_PACKAGE_NAMES = [
    "com.mercadopago.wallet",
    "com.paypal.android.p2pmobile",
];

export const MESSAGING_PACKAGE_NAMES = [
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
];

export const SENSITIVE_PACKAGE_NAMES = [
    ...MESSAGING_PACKAGE_NAMES,
    ...MONEY_PACKAGE_NAMES,
];

// Substring tokens that make any package money. `pay` also covers Google Pay
// and wallet ids that do not use the exact names above.
export const MONEY_PACKAGE_TOKENS = ["bank", "banco", "pay", "wallet"];
export const SENSITIVE_PACKAGE_TOKENS = MONEY_PACKAGE_TOKENS;

// Chilean banks whose package id does not carry a token above.
export const MONEY_PACKAGE_PREFIXES = [
    "cl.santander",
    "com.santander",
    "cl.bci.",
    "com.bci.",
];
export const SENSITIVE_PACKAGE_PREFIXES = MONEY_PACKAGE_PREFIXES;

/** Reason codes stored in an approval and printed by the CLI. */
export const RISK_REASONS = {
    typeSubmit: "type_submit",
    riskyText: "risky_text",
    sensitiveApp: "sensitive_app",
    phoneDetected: "phone_detected_risky_target",
};

/**
 * Extra detail stored on an approval when the destructive step has a shape the
 * phone must word differently (phone-dashboard, T6). `data_wipe` means the tap
 * clears app data or storage: it is gated like any other destructive step and
 * the bubble says what is lost before asking. The value is additive; older
 * clients ignore it.
 */
export const RISK_KINDS = {
    dataWipe: "data_wipe",
};

// A data wipe clears the app's data or the device storage; clearing the cache
// never matches. Up to two filler words are allowed so the confirmation-sheet
// variants ("borrar todos los datos", "clear app data") are caught too. This
// only recognizes the phrase: the command is gated as any destructive step,
// never refused (owner correction, phone-dashboard T6).
const DATA_WIPE_PATTERN = new RegExp(
    "(^|[^\\p{L}\\p{N}])(borrar|eliminar|clear)(?:\\s+[\\p{L}]+){0,2}\\s+" +
    "(datos|almacenamiento|data|storage)(?=$|[^\\p{L}\\p{N}])",
    "u",
);

/** True when the text asks to clear app data or storage, in Spanish or English. */
export function isDataWipeText(value) {
    const normalized = normalizeRiskText(value);
    if (normalized.length === 0) return false;
    return DATA_WIPE_PATTERN.test(normalized);
}

// Most restrictive first: "Confirmar pago" is money, not a confirmation, and
// "Eliminar y confirmar" is destructive.
const CATEGORY_PRECEDENCE = ["destructive", "money", "confirmation", "message"];

const CATEGORY_PATTERNS = Object.fromEntries(CATEGORY_PRECEDENCE.map((category) => [
    category,
    new RegExp(
        `(^|[^\\p{L}\\p{N}])(${RISKY_VERB_CATEGORIES[category].join("|")})(?=$|[^\\p{L}\\p{N}])`,
        "u",
    ),
]));

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

/**
 * Category of the risky verb in `text` ("message", "money", "destructive" or
 * "confirmation"), or null when the text is not risky. The most restrictive
 * category wins when a phrase carries more than one verb.
 */
export function riskyTextCategory(text) {
    const normalized = normalizeRiskText(text);
    if (normalized.length === 0) return null;
    for (const category of CATEGORY_PRECEDENCE) {
        if (CATEGORY_PATTERNS[category].test(normalized)) return category;
    }
    return null;
}

/** True when the text is, or contains as a whole word, a risky verb. */
export function isRiskyText(text) {
    const normalized = normalizeRiskText(text);
    if (normalized.length === 0) return false;
    return RISKY_TEXT_PATTERN.test(normalized);
}

/** True for bank, wallet and payment packages. */
export function isMoneyPackage(packageName) {
    if (typeof packageName !== "string" || packageName.length === 0) return false;
    const name = packageName.toLowerCase();
    if (MONEY_PACKAGE_NAMES.includes(name)) return true;
    if (MONEY_PACKAGE_TOKENS.some((token) => name.includes(token))) return true;
    return MONEY_PACKAGE_PREFIXES.some((prefix) => name.startsWith(prefix));
}

/** True for messaging, chat and email packages. */
export function isMessagingPackage(packageName) {
    if (typeof packageName !== "string" || packageName.length === 0) return false;
    return MESSAGING_PACKAGE_NAMES.includes(packageName.toLowerCase());
}

/** True for messaging, email, payment and banking packages. */
export function isSensitivePackage(packageName) {
    return isMessagingPackage(packageName) || isMoneyPackage(packageName);
}

function targetOf(args) {
    if (typeof args.id === "string" && args.id.length > 0) return args.id;
    if (Number.isInteger(args.x) && Number.isInteger(args.y)) return `${args.x},${args.y}`;
    return null;
}

/**
 * Classifies one already-normalized command.
 *
 * `category` is only present on gated verdicts: it tells an owner bubble turn
 * which steps its request already approves ("message") and which still wait
 * ("money", "destructive", "confirmation", "sensitive_app").
 *
 * `riskKind` is optional and only present when the phone must word the
 * destructive step differently (`data_wipe`, phone-dashboard T6).
 *
 * @param {{type?: string, args?: object}} command
 * @param {{foregroundPackage?: string | null}} [context]
 * @returns {{gated: boolean, reason: string, target?: string, category?: string, riskKind?: string}}
 */
export function classifyCommand(command, { foregroundPackage = null } = {}) {
    const type = command && typeof command === "object" ? command.type : undefined;
    const args = command && typeof command === "object" && command.args &&
        typeof command.args === "object" && !Array.isArray(command.args)
        ? command.args
        : {};

    if (type === "type") {
        if (args.submit === true) {
            // Sending composed text is a messaging step: an owner turn covers
            // it, the money/destructive categories never apply here.
            return { gated: true, reason: RISK_REASONS.typeSubmit, category: "message" };
        }
        // Composing text changes nothing until a send: the submit flag above
        // and the phone-side node check own that boundary.
        return { gated: false, reason: "free" };
    }

    if (type === "settings") {
        // phone-dashboard T7: a fixed settings screen is navigation, like
        // `open`. The allowlist is enforced by the store, so nothing here can
        // become a free-form intent.
        return { gated: false, reason: "free" };
    }

    if (type === "tap" || type === "long_press") {
        if (typeof args.text === "string") {
            // A data wipe is destructive like any other delete, but the owner
            // asked for a reasoned question, not a hard block: the approval
            // carries the kind so the phone can say what is lost (T6).
            if (isDataWipeText(args.text)) {
                return {
                    gated: true,
                    reason: RISK_REASONS.riskyText,
                    target: args.text,
                    category: "destructive",
                    riskKind: RISK_KINDS.dataWipe,
                };
            }
            const category = riskyTextCategory(args.text);
            if (category !== null) {
                return {
                    gated: true,
                    reason: RISK_REASONS.riskyText,
                    target: args.text,
                    category,
                };
            }
        }
        const target = targetOf(args);
        if (target !== null && isSensitivePackage(foregroundPackage)) {
            return {
                gated: true,
                reason: RISK_REASONS.sensitiveApp,
                target,
                category: isMoneyPackage(foregroundPackage) ? "money" : "message",
            };
        }
        return { gated: false, reason: "free" };
    }

    return { gated: false, reason: "free" };
}
