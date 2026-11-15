package com.sherwood.wta.core.frontend

import android.content.Context
import android.util.Base64
import com.sherwood.wta.core.crypto.EnhancedCrypto
import com.sherwood.wta.core.logging.AppLogger
import java.nio.charset.StandardCharsets

/**
 * How a stored credential is protected at rest.
 *
 * [NONE] — nothing stored.
 * [ANDROID_KEYSTORE] — AES-GCM with a key the Android Keystore owns; the key material
 *   cannot be extracted from the device.
 * [APP_DERIVED_FALLBACK] — the Keystore master key was unavailable, so the key is
 *   derived from the package name plus the APK signing certificate. That certificate is
 *   public (anyone holding the APK can read it), so this is obfuscation, not secret-key
 *   encryption: it still protects against accidental plaintext exposure in a backup or
 *   cloud sync, but not against an attacker who reads the app's private storage.
 *
 * The UI surfaces this so a degraded device is never silently presented as
 * hardware-protected. Nothing here weakens the storage path.
 */
enum class TokenProtection { NONE, ANDROID_KEYSTORE, APP_DERIVED_FALLBACK }

/**
 * AES-GCM wrapping for credentials at rest, shared by every git credential slot.
 *
 * A stored value is a Base64 ciphertext, or [FALLBACK_PREFIX] followed by one when the
 * Android Keystore master key was unavailable. The associated data is per call site, so
 * a ciphertext written for one slot cannot be replayed into another.
 *
 * This was previously private to [GitHubTokenStore]. It is shared now because the Studio
 * stores one credential per hosting provider, and two copies of the encryption rules
 * would be two chances to drift.
 */
object TokenCipher {

    /** Marks a value encrypted with the app-derived key instead of the Keystore. */
    const val FALLBACK_PREFIX = "FALLBACK:"

    private const val TAG = "TokenCipher"

    fun encrypt(context: Context, plaintext: String, associatedData: ByteArray): String {
        val masterKey = EnhancedCrypto.getOrCreateMasterKey(context)
        if (masterKey != null) {
            val ciphertext = EnhancedCrypto.encrypt(
                plaintext.toByteArray(StandardCharsets.UTF_8),
                masterKey,
                associatedData
            )
            return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }
        // Fallback when Android Keystore is unavailable on this device: derive a key from
        // the app signature + package name. Less secure than Keystore-backed encryption,
        // but keeps the credential encrypted at rest instead of plaintext.
        AppLogger.w(TAG, "Android Keystore unavailable, falling back to app-derived key")
        val container = EnhancedCrypto.deriveAppKey(context.packageName, signature(context))
        val key = container.getKey()
        try {
            val ciphertext = EnhancedCrypto.encrypt(
                plaintext.toByteArray(StandardCharsets.UTF_8),
                key,
                associatedData
            )
            return FALLBACK_PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } finally {
            container.close()
        }
    }

    fun decrypt(context: Context, encoded: String, associatedData: ByteArray): String {
        val masterKey = EnhancedCrypto.getOrCreateMasterKey(context)
        if (masterKey != null && !encoded.startsWith(FALLBACK_PREFIX)) {
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext = EnhancedCrypto.decrypt(ciphertext, masterKey, associatedData)
            return String(plaintext, StandardCharsets.UTF_8)
        }
        val container = EnhancedCrypto.deriveAppKey(context.packageName, signature(context))
        val key = container.getKey()
        try {
            val payload = encoded.removePrefix(FALLBACK_PREFIX)
            val ciphertext = Base64.decode(payload, Base64.NO_WRAP)
            val plaintext = EnhancedCrypto.decrypt(ciphertext, key, associatedData)
            return String(plaintext, StandardCharsets.UTF_8)
        } finally {
            container.close()
        }
    }

    /** Classifies a stored value without decrypting it. */
    fun protection(encoded: String?): TokenProtection = when {
        encoded == null -> TokenProtection.NONE
        encoded.startsWith(FALLBACK_PREFIX) -> TokenProtection.APP_DERIVED_FALLBACK
        else -> TokenProtection.ANDROID_KEYSTORE
    }

    /**
     * The APK signing certificate, which is public. Deliberately not wrapped in
     * `runCatching`: if the package info cannot be read, the derived key must not
     * silently become a key derived from an empty signature. Callers already treat a
     * throw here as "the credential could not be stored or read".
     */
    private fun signature(context: Context): ByteArray =
        context.packageManager.getPackageInfo(context.packageName, 0)
            .signatures?.firstOrNull()?.toByteArray() ?: ByteArray(0)
}
