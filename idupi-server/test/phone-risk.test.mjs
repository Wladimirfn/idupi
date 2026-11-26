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
    isRiskyText,
    isSensitivePackage,
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
        assert.deepEqual(verdict, { gated: true, reason: "risky_text", target: "Enviar" });
    }
});

test("type with submit is gated even when the text is benign", () => {
    assert.deepEqual(classifyCommand({ type: "type", args: { text: "hola", submit: true } }), {
        gated: true,
        reason: "type_submit",
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
    });

    const byXY = classifyCommand(
        { type: "long_press", args: { x: 900, y: 2100 } },
        { foregroundPackage: "com.paypal.android.p2pmobile" },
    );
    assert.deepEqual(byXY, { gated: true, reason: "sensitive_app", target: "900,2100" });

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
