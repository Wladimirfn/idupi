// Tests for the phone command risk classifier (phone-approvals, T1).
//
// The classifier is pure: the same command plus the foreground package always
// answers the same gate. Reading and navigation must stay free; outbound or
// irreversible actions run only after the owner approves on the phone.
//
// Run (from repo root):
//   node --test idupi-server/test/phone-risk.test.mjs

import test from "node:test";
import assert from "node:assert/strict";

import {
    classifyCommand,
    isDataWipeText,
    isMessagingPackage,
    isMoneyPackage,
    isRiskyText,
    isSensitivePackage,
    riskyTextCategory,
} from "../lib/phone-risk.mjs";

test("reading, waiting and navigation commands are never gated", () => {
    const free = [
        { type: "screen", args: {} },
        { type: "status", args: {} },
        { type: "scroll", args: { direction: "down" } },
        { type: "global", args: { action: "back" } },
        { type: "global", args: { action: "quick_settings" } },
        { type: "open", args: { app: "com.whatsapp" } },
        { type: "wait", args: { ms: 250 } },
        // phone-dashboard T7: opening one of the fixed settings screens is
        // navigation, like `open`; nothing is written by the intent itself.
        { type: "settings", args: { screen: "device-care" } },
        { type: "settings", args: { screen: "storage" } },
        { type: "settings", args: { screen: "battery" } },
        { type: "settings", args: { screen: "apps" } },
        { type: "settings", args: { screen: "app", package: "com.whatsapp" } },
    ];
    for (const command of free) {
        const verdict = classifyCommand(command);
        assert.equal(verdict.gated, false, `${command.type} should be free`);
    }
});

test("a benign tap or type is free even inside a chat app", () => {
    for (const command of [
        { type: "tap", args: { text: "Ana" } },
        { type: "tap", args: { text: "Buscar" } },
        { type: "long_press", args: { text: "Mensaje" } },
        { type: "type", args: { text: "hola" } },
    ]) {
        const verdict = classifyCommand(command, { foregroundPackage: "com.whatsapp" });
        assert.equal(verdict.gated, false, JSON.stringify(command));
    }
});

test("risk text matches risky verbs as whole words, ignoring case and accents", () => {
    const risky = [
        "Enviar",
        "ENVIAR",
        "enviár",
        "Enviar ahora",
        "Send",
        "Pagar",
        "pagar la cuenta",
        "Pay",
        "Eliminar chat",
        "Borrar",
        "Delete message",
        "Aprobar",
        "Approve request",
        "Confirmar pago",
        "Confirm",
        "Comprar",
        "Buy now",
        "Transferir",
        "Transfer funds",
        "Publicar",
        "Post",
        "Share",
        "Compartir",
        // phone-dashboard T3: uninstalling an app is irreversible.
        "Desinstalar",
        "desinstala",
        "Uninstall",
        "UNINSTALL",
    ];
    for (const text of risky) {
        assert.equal(isRiskyText(text), true, `"${text}" should be risky`);
    }

    const free = [
        "Reenviar",
        "Pagaré mañana",
        "Repayment",
        "Aceptar",
        "Responder",
        "Cancelar",
        "Desinstalado",
        "",
        null,
        undefined,
        42,
    ];
    for (const text of free) {
        assert.equal(isRiskyText(text), false, `${String(text)} should be free`);
    }
});

test("tap and long-press on risky text are gated with the text as target", () => {
    for (const type of ["tap", "long_press"]) {
        const verdict = classifyCommand({ type, args: { text: "Enviar" } });
        assert.deepEqual(verdict, {
            gated: true,
            reason: "risky_text",
            target: "Enviar",
            category: "message",
        });
    }
});

test("type with submit is gated even when the text is benign", () => {
    assert.deepEqual(classifyCommand({ type: "type", args: { text: "hola", submit: true } }), {
        gated: true,
        reason: "type_submit",
        category: "message",
    });
    assert.equal(
        classifyCommand({ type: "type", args: { text: "hola", submit: false } }).gated,
        false,
    );
});

test("id and coordinate taps are gated while a sensitive app is in front", () => {
    const byId = classifyCommand(
        { type: "tap", args: { id: "com.whatsapp:id/send" } },
        { foregroundPackage: "com.whatsapp" },
    );
    assert.deepEqual(byId, {
        gated: true,
        reason: "sensitive_app",
        target: "com.whatsapp:id/send",
        category: "message",
    });

    const byXY = classifyCommand(
        { type: "long_press", args: { x: 900, y: 2100 } },
        { foregroundPackage: "com.paypal.android.p2pmobile" },
    );
    assert.deepEqual(byXY, {
        gated: true,
        reason: "sensitive_app",
        target: "900,2100",
        category: "money",
    });

    // Composing text without submit stays free even inside a chat app.
    assert.equal(
        classifyCommand(
            { type: "type", args: { text: "te transfiero" } },
            { foregroundPackage: "com.whatsapp" },
        ).gated,
        false,
    );
});

test("id and coordinate taps stay free without a sensitive foreground app", () => {
    for (const command of [
        { type: "tap", args: { id: "com.android.settings:id/ok" } },
        { type: "tap", args: { x: 10, y: 20 } },
        { type: "long_press", args: { x: 10, y: 20 } },
    ]) {
        assert.equal(classifyCommand(command).gated, false, JSON.stringify(command));
        assert.equal(
            classifyCommand(command, { foregroundPackage: "com.android.settings" }).gated,
            false,
        );
    }
});

test("sensitive packages cover messaging, payment, email and banking apps", () => {
    const sensitive = [
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.instagram.android",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "org.thoughtcrime.securesms",
        "com.mercadopago.wallet",
        "com.paypal.android.p2pmobile",
        "cl.santander.santanderchile",
        "cl.bci.sismo.mach",
        "cl.bancoestado.app",
        "com.some.bank.app",
        "ar.com.banconorte",
        "com.example.digitalwallet",
    ];
    for (const packageName of sensitive) {
        assert.equal(isSensitivePackage(packageName), true, `${packageName} should be sensitive`);
    }

    for (const packageName of [
        "com.android.settings",
        "com.spotify.music",
        null,
        "",
    ]) {
        assert.equal(isSensitivePackage(packageName), false, `${String(packageName)} is free`);
    }
});

// --- owner turn categories (phone-bubble, T13) -----------------------------

test("risky verbs carry the category an owner turn uses to decide", () => {
    const cases = {
        message: ["Enviar", "Send", "Enviar ahora", "Compartir", "Share", "Publicar", "Post"],
        money: ["Pagar", "pagar la cuenta", "Pay", "Comprar", "Buy now", "Transferir", "Transfer funds"],
        destructive: [
            "Eliminar chat",
            "Borrar",
            "Delete message",
            "Desinstalar WhatsApp",
            "Uninstall app",
        ],
        confirmation: ["Aprobar", "Approve request", "Confirmar", "Confirm"],
    };
    for (const [category, texts] of Object.entries(cases)) {
        for (const text of texts) {
            assert.equal(riskyTextCategory(text), category, `"${text}" should be ${category}`);
            assert.equal(classifyCommand({ type: "tap", args: { text } }).category, category);
        }
    }
    assert.equal(riskyTextCategory("Reenviar"), null);
    assert.equal(riskyTextCategory("Aceptar"), null);
    assert.equal(riskyTextCategory("Desinstalado"), null);

    // The most restrictive verb wins when a phrase carries two.
    assert.equal(riskyTextCategory("Pagar y confirmar"), "money");
    assert.equal(riskyTextCategory("Eliminar y confirmar"), "destructive");
});

// --- data wipe is destructive and gated (phone-dashboard, T3 + T6) --------

test("clearing data or storage is recognized as a data wipe", () => {
    const wipes = [
        "Borrar datos",
        "BORRAR DATOS",
        "Borrar datos de la app",
        "Borrar los datos",
        "Borrar almacenamiento",
        "borrar el almacenamiento",
        "Eliminar datos",
        "eliminar los datos de la app",
        "Clear data",
        "clear app data",
        "Clear storage",
        "CLEAR STORAGE",
    ];
    for (const text of wipes) {
        assert.equal(isDataWipeText(text), true, `"${text}" should be a data wipe`);
    }

    const safe = [
        "Borrar caché",
        "Borrar cache",
        "Clear cache",
        "Eliminar chat",
        "Borrar",
        "Almacenamiento",
        "Caché 1,2 GB",
        "",
        null,
        undefined,
        42,
    ];
    for (const text of safe) {
        assert.equal(isDataWipeText(text), false, `"${text}" is not a data wipe`);
    }
});

test("a data wipe tap is gated as a destructive step, not a hard block", () => {
    const wipes = [
        "Borrar datos",
        "Borrar los datos",
        "Borrar almacenamiento",
        "Eliminar datos",
        "Clear data",
        "clear app data",
        "Clear storage",
    ];
    for (const type of ["tap", "long_press"]) {
        for (const text of wipes) {
            const verdict = classifyCommand({ type, args: { text } });
            assert.equal(verdict.gated, true, `${type} "${text}" is gated`);
            assert.equal(verdict.category, "destructive", `"${text}" is destructive`);
            assert.equal(verdict.reason, "risky_text", `"${text}" keeps the destructive reason`);
            assert.equal(verdict.riskKind, "data_wipe", `"${text}" carries the data-wipe kind`);
            assert.equal(verdict.target, text);
        }
    }

    // "clear" alone never becomes a risky verb: only the full data-wipe
    // phrase is gated, so an English cache clear stays a free tap.
    const clearCache = classifyCommand({ type: "tap", args: { text: "Clear cache" } });
    assert.equal(clearCache.gated, false);

    // A cache clear keeps its ordinary destructive gate, without the kind.
    const cache = classifyCommand({ type: "tap", args: { text: "Borrar caché" } });
    assert.equal(cache.gated, true);
    assert.equal(cache.category, "destructive");
    assert.equal(cache.riskKind, undefined);
});

test("messaging and money apps are distinguished inside the sensitive set", () => {
    for (const packageName of [
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.google.android.apps.messaging",
        "org.thoughtcrime.securesms",
    ]) {
        assert.equal(isMessagingPackage(packageName), true, `${packageName} is messaging`);
        assert.equal(isMoneyPackage(packageName), false, `${packageName} is not money`);
    }

    for (const packageName of [
        "com.mercadopago.wallet",
        "com.paypal.android.p2pmobile",
        "cl.santander.santanderchile",
        "cl.bancoestado.app",
        "com.some.bank.app",
        "com.example.digitalwallet",
    ]) {
        assert.equal(isMoneyPackage(packageName), true, `${packageName} is money`);
        assert.equal(isMessagingPackage(packageName), false, `${packageName} is not messaging`);
    }

    for (const packageName of ["com.android.settings", "com.spotify.music", "", null]) {
        assert.equal(isMessagingPackage(packageName), false);
        assert.equal(isMoneyPackage(packageName), false);
    }
});

test("a sensitive messaging app is a message step, a bank app is money", () => {
    const messaging = classifyCommand(
        { type: "tap", args: { id: "com.whatsapp:id/send" } },
        { foregroundPackage: "com.whatsapp" },
    );
    assert.equal(messaging.category, "message");

    const banking = classifyCommand(
        { type: "tap", args: { id: "cl.bancoestado.app:id/transfer" } },
        { foregroundPackage: "cl.bancoestado.app" },
    );
    assert.equal(banking.category, "money");
});
