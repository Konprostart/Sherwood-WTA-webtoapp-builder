package com.sherwood.wta.core.update

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.model.AppUpdateConfig
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppUpdateManifestTest {

    private val sha = "a".repeat(64)
    private lateinit var keyPair: KeyPair
    private lateinit var publicKeyBase64: String

    @Before
    fun setUp() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        keyPair = generator.generateKeyPair()
        publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    }

    private fun sign(
        versionCode: Long,
        versionName: String,
        apkUrl: String,
        sha256: String,
        key: java.security.PrivateKey = keyPair.private
    ): String {
        val payload = "$versionCode|$versionName|$apkUrl|$sha256".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(payload)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    private fun manifestJson(
        versionCode: Long = 3,
        versionName: String = "1.2.0",
        apkUrl: String = "https://example.com/app-1.2.0.apk",
        sha256: String = sha,
        sig: String = sign(versionCode, versionName, apkUrl, sha256)
    ): String = """
        {"versionCode": $versionCode, "versionName": "$versionName", "apkUrl": "$apkUrl",
         "sha256": "$sha256", "sig": "$sig"}
    """.trimIndent()

    @Test
    fun `signed newer manifest yields an update`() {
        val verdict = AppUpdateManifest.evaluate(manifestJson(), publicKeyBase64, installedVersionCode = 2)
        assertThat(verdict).isInstanceOf(AppUpdateManifest.Verdict.Update::class.java)
        val update = verdict as AppUpdateManifest.Verdict.Update
        assertThat(update.manifest.versionName).isEqualTo("1.2.0")
        assertThat(update.manifest.apkUrl).isEqualTo("https://example.com/app-1.2.0.apk")
    }

    @Test
    fun `same version is up to date`() {
        val verdict = AppUpdateManifest.evaluate(manifestJson(), publicKeyBase64, installedVersionCode = 3)
        assertThat(verdict).isEqualTo(AppUpdateManifest.Verdict.UpToDate)
    }

    @Test
    fun `older signed version is refused as rollback`() {
        val verdict = AppUpdateManifest.evaluate(manifestJson(), publicKeyBase64, installedVersionCode = 5)
        assertThat(verdict).isInstanceOf(AppUpdateManifest.Verdict.Rejected::class.java)
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).contains("rollback")
    }

    @Test
    fun `tampered field invalidates the signature`() {
        val original = manifestJson()
        val tampered = original.replace("app-1.2.0.apk", "evil-1.2.0.apk")
        val verdict = AppUpdateManifest.evaluate(tampered, publicKeyBase64, installedVersionCode = 2)
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).isEqualTo("signature does not match")
    }

    @Test
    fun `manifest signed by another key is rejected`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val attacker = generator.generateKeyPair()
        val sig = sign(3, "1.2.0", "https://example.com/app-1.2.0.apk", sha, attacker.private)
        val verdict = AppUpdateManifest.evaluate(
            manifestJson(sig = sig), publicKeyBase64, installedVersionCode = 2
        )
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).isEqualTo("signature does not match")
    }

    @Test
    fun `non https apk url is rejected before signature check`() {
        val url = "http://example.com/app.apk"
        val verdict = AppUpdateManifest.evaluate(
            manifestJson(apkUrl = url, sig = "AAAA"), publicKeyBase64, installedVersionCode = 2
        )
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).isEqualTo("apkUrl must use https")
    }

    @Test
    fun `malformed sha256 is rejected`() {
        val verdict = AppUpdateManifest.evaluate(
            manifestJson(sha256 = "not-hex", sig = "AAAA"), publicKeyBase64, installedVersionCode = 2
        )
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).contains("sha256")
    }

    @Test
    fun `missing field and garbage input are malformed`() {
        assertThat(AppUpdateManifest.parse("{\"versionCode\": 3}")).isNull()
        assertThat(AppUpdateManifest.parse("not json")).isNull()
        val verdict = AppUpdateManifest.evaluate("not json", publicKeyBase64, installedVersionCode = 2)
        assertThat((verdict as AppUpdateManifest.Verdict.Rejected).reason).isEqualTo("malformed manifest")
    }

    @Test
    fun `invalid public key never verifies`() {
        assertThat(AppUpdateManifest.verifySignature(
            AppUpdateManifest.parse(manifestJson())!!, "bm90LWEta2V5"
        )).isFalse()
    }

    @Test
    fun `check interval respects the configured hours`() {
        val hour = 60L * 60L * 1000L
        assertThat(AppUpdateManifest.isCheckDue(nowMillis = 10 * hour, lastCheckedAtMillis = 0L, intervalHours = 24)).isTrue()
        assertThat(AppUpdateManifest.isCheckDue(nowMillis = 10 * hour, lastCheckedAtMillis = 9 * hour, intervalHours = 24)).isFalse()
        assertThat(AppUpdateManifest.isCheckDue(nowMillis = 30 * hour, lastCheckedAtMillis = 5 * hour, intervalHours = 24)).isTrue()
    }

    @Test
    fun `config is usable only when enabled with url and key`() {
        assertThat(AppUpdateConfig().isUsable).isFalse()
        assertThat(AppUpdateConfig(enabled = true, manifestUrl = "https://x", publicKeyBase64 = "").isUsable).isFalse()
        assertThat(AppUpdateConfig(enabled = true, manifestUrl = "https://x", publicKeyBase64 = "k").isUsable).isTrue()
    }
}
