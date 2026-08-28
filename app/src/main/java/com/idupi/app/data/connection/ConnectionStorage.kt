package com.idupi.app.data.connection

import android.content.Context
import com.idupi.app.domain.model.ConnectionMode
import com.idupi.app.domain.model.ConnectionProfile
import com.idupi.app.domain.model.TransportType

class ConnectionStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("idupi_connection_prefs", Context.MODE_PRIVATE)

    /**
     * Persists [profile]. Returns false when the token could not be encrypted,
     * in which case everything except the token was still saved. Callers must
     * not report success to the user without checking this.
     */
    fun saveProfile(profile: ConnectionProfile): Boolean {
        val encryptedToken = TokenCipher.encrypt(profile.token)
        prefs.edit().apply {
            putString("profile_name", profile.name)
            putString("profile_mode", profile.mode.name)
            putString("profile_host", profile.host)
            putInt("profile_port", profile.port)
            if (encryptedToken != null) {
                putString("profile_token_enc", encryptedToken)
            } else {
                remove("profile_token_enc")
            }
            // Never keep the legacy plaintext token around once we save.
            remove("profile_token")
            putBoolean("profile_use_https", profile.useHttps)
            putString("profile_transport", profile.transportType.name)
            apply()
        }
        // An empty token is a valid choice; only a failed encryption is a problem.
        return profile.token.isEmpty() || encryptedToken != null
    }

    fun getProfile(): ConnectionProfile? {
        val name = prefs.getString("profile_name", null) ?: return null
        val modeStr = prefs.getString("profile_mode", ConnectionMode.TAILSCALE.name) ?: ConnectionMode.TAILSCALE.name
        val mode = try { ConnectionMode.valueOf(modeStr) } catch (e: Exception) { ConnectionMode.TAILSCALE }

        val transportStr = prefs.getString("profile_transport", TransportType.AUTO.name) ?: TransportType.AUTO.name
        val transportType = try { TransportType.valueOf(transportStr) } catch (e: Exception) { TransportType.AUTO }

        val host = prefs.getString("profile_host", "") ?: ""
        val port = prefs.getInt("profile_port", 8787)
        val token = resolveToken()
        val useHttps = prefs.getBoolean("profile_use_https", false)
        return ConnectionProfile(name, mode, host, port, token, useHttps, transportType)
    }

    /**
     * Resolves the stored token, transparently migrating a legacy plaintext
     * token to the encrypted storage key the first time it is read.
     */
    private fun resolveToken(): String {
        val encrypted = prefs.getString("profile_token_enc", null)
        if (encrypted != null) {
            return TokenCipher.decrypt(encrypted) ?: ""
        }

        val legacyPlainToken = prefs.getString("profile_token", null)
        if (legacyPlainToken != null) {
            val reEncrypted = TokenCipher.encrypt(legacyPlainToken)
            prefs.edit().apply {
                if (reEncrypted != null) {
                    putString("profile_token_enc", reEncrypted)
                }
                remove("profile_token")
                apply()
            }
            return legacyPlainToken
        }

        return ""
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
