package com.sherwood.wta.core.update

import android.util.Base64
import com.google.gson.JsonParser
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Update manifest for an app built with Sherwood WTA (auto-update).
 *
 * The manifest is a JSON object served over HTTPS by the app owner:
 *
 * ```
 * {
 *   "versionCode": 3,
 *   "versionName": "1.2.0",
 *   "apkUrl": "https://example.com/app-1.2.0.apk",
 *   "sha256": "<64 hex characters>",
 *   "sig": "<base64 DER ECDSA-SHA256 signature>"
 * }
 * ```
 *
 * The signature covers the UTF-8 string `versionCode|versionName|apkUrl|sha256` and is
 * made with the EC P-256 private key whose public half (SPKI, Base64) is embedded in the
 * generated app. A manifest that is malformed, unsigned, not HTTPS, or offers an older
 * version is rejected. Nothing in this object touches the network or the file system.
 */
object AppUpdateManifest {

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    data class Manifest(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val sig: String
    )

    sealed class Verdict {
        /** The manifest is authentic and offers a newer version. */
        data class Update(val manifest: Manifest) : Verdict()

        /** The installed version is already the newest signed version. */
        data object UpToDate : Verdict()

        /** The manifest must not be used. [reason] is a developer-facing message. */
        data class Rejected(val reason: String) : Verdict()
    }

    /** Parses the manifest JSON. Returns null when a field is missing or has the wrong type. */
    fun parse(json: String): Manifest? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        Manifest(
            versionCode = obj.get("versionCode").asLong,
            versionName = obj.get("versionName").asString,
            apkUrl = obj.get("apkUrl").asString,
            sha256 = obj.get("sha256").asString,
            sig = obj.get("sig").asString
        )
    } catch (e: Exception) {
        null
    }

    /** The exact bytes that the manifest signature covers. */
    fun signedPayload(manifest: Manifest): ByteArray =
        "${manifest.versionCode}|${manifest.versionName}|${manifest.apkUrl}|${manifest.sha256}"
            .toByteArray(Charsets.UTF_8)

    /** Verifies the ECDSA signature with an EC P-256 public key (SPKI, Base64, PEM markers optional). */
    fun verifySignature(manifest: Manifest, publicKeyBase64: String): Boolean = try {
        val keyBytes = decodeBase64(publicKeyBase64.replace(Regex("-----[A-Z ]+-----"), ""))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(signedPayload(manifest))
        verifier.verify(decodeBase64(manifest.sig))
    } catch (e: Exception) {
        false
    }

    /**
     * Full check of a manifest response against the embedded public key and the installed
     * version. Checks run in this order: parse, HTTPS URL, SHA-256 format, signature, version.
     */
    fun evaluate(json: String, publicKeyBase64: String, installedVersionCode: Long): Verdict {
        val manifest = parse(json) ?: return Verdict.Rejected("malformed manifest")
        if (!isHttps(manifest.apkUrl)) return Verdict.Rejected("apkUrl must use https")
        if (!SHA256_HEX.matches(manifest.sha256)) return Verdict.Rejected("sha256 is not a 64-character hex digest")
        if (!verifySignature(manifest, publicKeyBase64)) return Verdict.Rejected("signature does not match")
        return when {
            manifest.versionCode > installedVersionCode -> Verdict.Update(manifest)
            manifest.versionCode == installedVersionCode -> Verdict.UpToDate
            else -> Verdict.Rejected("manifest offers an older version (rollback refused)")
        }
    }

    /** True when at least [intervalHours] have passed since [lastCheckedAtMillis]. */
    fun isCheckDue(nowMillis: Long, lastCheckedAtMillis: Long, intervalHours: Int): Boolean {
        if (lastCheckedAtMillis <= 0L) return true
        val intervalMillis = intervalHours.coerceAtLeast(1) * 60L * 60L * 1000L
        return nowMillis - lastCheckedAtMillis >= intervalMillis
    }

    private fun isHttps(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    } catch (e: Exception) {
        false
    }

    private fun decodeBase64(value: String): ByteArray =
        Base64.decode(value.trim(), Base64.DEFAULT)
}
