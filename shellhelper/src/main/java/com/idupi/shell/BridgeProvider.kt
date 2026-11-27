package com.idupi.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * The app <-> helper channel (phone-shell T4 contract).
 *
 * Exported but protected by the signature permission
 * `com.idupi.shell.permission.BRIDGE`, and every call repeats the check in code
 * (exact package plus signature match). Only `call` is implemented: the
 * bridge is not a data store.
 */
class BridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        verifyCaller()
        return when (method) {
            METHOD_STATUS -> statusBundle()
            METHOD_SHELL -> shellBundle(extras)
            METHOD_KICK -> kickBundle()
            else -> errorBundle("método desconocido")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("bridge provider is call-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("bridge provider is call-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("bridge provider is call-only")

    private fun verifyCaller() {
        val caller = callingPackage
        if (caller != APP_PACKAGE) {
            throw SecurityException("unauthorized caller")
        }
        val providerContext = context ?: throw SecurityException("helper without context")
        val match = providerContext.packageManager.checkSignatures(caller, providerContext.packageName)
        if (match != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("signature mismatch")
        }
    }

    private fun statusBundle(): Bundle {
        val active = BridgeState.isActive()
        return Bundle().apply {
            putBoolean(RESULT_OK, true)
            putBoolean(RESULT_ACTIVE, active)
            putString(RESULT_TEXT, if (active) "Puente activo." else "Puente inactivo.")
        }
    }

    private fun shellBundle(extras: Bundle?): Bundle {
        if (!BridgeState.isActive()) {
            return errorBundle("El puente no está activo. Inícialo desde IDUPI.")
        }
        val validation = ShellRequestValidator.validate(
            action = extras?.getString(EXTRA_ACTION),
            packageName = extras?.getString(EXTRA_PACKAGE),
            mode = extras?.getString(EXTRA_MODE),
            topic = extras?.getString(EXTRA_TOPIC),
        )
        val request = when (validation) {
            is ShellValidation.Invalid -> return errorBundle(validation.message)
            is ShellValidation.Valid -> validation.request
        }
        val providerContext = context ?: return errorBundle("El ayudante no tiene contexto.")
        val result = ShellExecutor(providerContext).execute(request)
        return Bundle().apply {
            putBoolean(RESULT_OK, result.ok)
            putString(RESULT_TEXT, result.text)
            putString(RESULT_PATH, result.path)
            result.freeBefore?.let { putLong(RESULT_FREE_BEFORE, it) }
            result.freeAfter?.let { putLong(RESULT_FREE_AFTER, it) }
        }
    }

    private fun kickBundle(): Bundle {
        if (!BridgeState.isActive()) {
            return errorBundle("El puente no está activo. Inícialo desde IDUPI.")
        }
        val ok = BridgeState.kickAccessibility()
        return Bundle().apply {
            putBoolean(RESULT_OK, ok)
            putString(
                RESULT_TEXT,
                if (ok) "Accesibilidad reiniciada." else "No se pudo reiniciar la accesibilidad.",
            )
            putString(RESULT_PATH, "helper")
        }
    }

    private fun errorBundle(message: String): Bundle = Bundle().apply {
        putBoolean(RESULT_OK, false)
        putString(RESULT_TEXT, message)
    }

    private companion object {
        const val APP_PACKAGE = "com.idupi.app"

        const val METHOD_STATUS = "status"
        const val METHOD_SHELL = "shell"
        const val METHOD_KICK = "kick-accessibility"

        const val EXTRA_ACTION = "action"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TOPIC = "topic"

        const val RESULT_OK = "ok"
        const val RESULT_TEXT = "text"
        const val RESULT_PATH = "path"
        const val RESULT_ACTIVE = "active"
        const val RESULT_FREE_BEFORE = "freeBefore"
        const val RESULT_FREE_AFTER = "freeAfter"
    }
}
