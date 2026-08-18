package com.example.idupi.data.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts the connection auth token using an AES-256-GCM key
 * stored in the Android Keystore. The key never leaves the secure hardware
 * (when available) and is never exposed to application code.
 *
 * Encoded format: Base64(iv) + ":" + Base64(ciphertext), using NO_WRAP encoding.
 */
internal object TokenCipher {

    private const val TAG = "TokenCipher"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "idupi_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
        keyStore.load(null)

        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plainText] with a fresh IV. Returns null if the Keystore
     * operation fails, so callers can decide how to handle the failure.
     */
    fun encrypt(plainText: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivEncoded = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherTextEncoded = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            "$ivEncoded:$cipherTextEncoded"
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Failed to encrypt token: ${e.message}")
            null
        } catch (e: IOException) {
            // KeyStore.load can fail on a corrupted keystore.
            Log.w(TAG, "Failed to open keystore while encrypting: ${e.message}")
            null
        } catch (e: ProviderException) {
            // The keystore daemon can be unavailable, and key generation fails
            // this way on some devices. Unchecked, so it must be caught here.
            Log.w(TAG, "Keystore provider failure while encrypting: ${e.message}")
            null
        }
    }

    /**
     * Decrypts [encoded]. Fails safe: returns null instead of throwing if the
     * value is malformed or the Keystore key is missing/invalidated (e.g.
     * after a device restore or when app data is moved to a new device).
     */
    fun decrypt(encoded: String): String? {
        return try {
            val parts = encoded.split(":")
            if (parts.size != 2) {
                Log.w(TAG, "Malformed encrypted token: expected 2 parts, got ${parts.size}")
                return null
            }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Failed to decrypt token: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to decode encrypted token: ${e.message}")
            null
        } catch (e: IOException) {
            // KeyStore.load can fail on a corrupted keystore.
            Log.w(TAG, "Failed to open keystore while decrypting: ${e.message}")
            null
        } catch (e: ProviderException) {
            // The keystore daemon can be unavailable, and key access fails this
            // way on some devices. Unchecked, so it must be caught here.
            Log.w(TAG, "Keystore provider failure while decrypting: ${e.message}")
            null
        }
    }
}
