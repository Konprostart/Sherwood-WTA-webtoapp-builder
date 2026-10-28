package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecretScannerTest {

    private fun ruleIds(path: String, content: String? = null): List<String> =
        SecretScanner.scan(path, content?.toByteArray(Charsets.UTF_8)).map { it.ruleId }

    // ---- file names

    @Test
    fun `dotenv files are warned about`() {
        assertThat(ruleIds(".env")).containsExactly("env_file")
        assertThat(ruleIds("config/.env")).containsExactly("env_file")
        assertThat(ruleIds(".env.local")).containsExactly("env_file")
        assertThat(ruleIds(".env.production")).containsExactly("env_file")
    }

    @Test
    fun `dotenv templates are not findings`() {
        assertThat(ruleIds(".env.example")).isEmpty()
        assertThat(ruleIds(".env.sample")).isEmpty()
        assertThat(ruleIds(".env.template")).isEmpty()
        assertThat(ruleIds(".env.defaults")).isEmpty()
    }

    @Test
    fun `key and keystore files are warned about`() {
        assertThat(ruleIds("certs/server.pem")).containsExactly("key_file")
        assertThat(ruleIds("signing/release.p12")).containsExactly("key_file")
        assertThat(ruleIds("signing/release.PFX")).containsExactly("key_file")
        assertThat(ruleIds("app.jks")).containsExactly("key_file")
        assertThat(ruleIds("debug.keystore")).containsExactly("key_file")
        assertThat(ruleIds("tls/private.key")).containsExactly("key_file")
    }

    @Test
    fun `ssh private keys are warned about but public keys are not`() {
        assertThat(ruleIds("keys/id_rsa")).containsExactly("ssh_key")
        assertThat(ruleIds("keys/id_ed25519")).containsExactly("ssh_key")
        assertThat(ruleIds("keys/id_rsa.pub")).isEmpty()
        assertThat(ruleIds("keys/id_ed25519.pub")).isEmpty()
    }

    @Test
    fun `service account and firebase config files are warned about`() {
        assertThat(ruleIds("service-account-prod.json")).containsExactly("service_account")
        assertThat(ruleIds("app/google-services.json")).containsExactly("firebase_config")
    }

    @Test
    fun `ordinary names are not findings`() {
        assertThat(ruleIds("index.html")).isEmpty()
        assertThat(ruleIds("src/env.ts")).isEmpty()
        assertThat(ruleIds("docs/keyboard.md")).isEmpty()
        assertThat(ruleIds("assets/keys.json")).isEmpty()
    }

    // ---- content: blocking rules

    @Test
    fun `private key block is a blocking finding`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val findings = SecretScanner.scan("notes.txt", pem.toByteArray())
        assertThat(findings).containsExactly(
            SecretFinding("notes.txt", SecretSeverity.BLOCK, "private_key")
        )
    }

    @Test
    fun `public key block is not a finding`() {
        val pem = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkq\n-----END PUBLIC KEY-----"
        assertThat(ruleIds("pub.txt", pem)).isEmpty()
    }

    @Test
    fun `github token with the real length is blocked`() {
        val token = "ghp_" + "a1B2".repeat(9)
        val findings = SecretScanner.scan("config.js", "const t = '$token';".toByteArray())
        assertThat(findings.map { it.ruleId to it.severity })
            .containsExactly("github_token" to SecretSeverity.BLOCK)
    }

    @Test
    fun `short github-like string is not blocked`() {
        assertThat(ruleIds("config.js", "const t = 'ghp_short';")).isEmpty()
    }

    @Test
    fun `aws documentation example key is not blocked`() {
        assertThat(ruleIds("README.md", "key = AKIAIOSFODNN7EXAMPLE")).isEmpty()
    }

    @Test
    fun `aws access key with a real shape is blocked`() {
        assertThat(ruleIds("deploy.sh", "AWS_KEY=AKIAZ9Y8X7W6V5U4T3S2")).containsExactly("aws_access_key")
    }

    @Test
    fun `google, slack and stripe live keys are blocked`() {
        assertThat(ruleIds("a.js", "AIza" + "S".repeat(35))).containsExactly("google_api_key")
        assertThat(ruleIds("b.js", "xoxb-1234567890-abcdefghij")).containsExactly("slack_token")
        assertThat(ruleIds("c.js", "sk_live_" + "x".repeat(24))).containsExactly("stripe_live_key")
    }

    // ---- content: warnings

    @Test
    fun `generic password assignment with digits and letters is warned about`() {
        val findings = SecretScanner.scan("app.py", "password = \"hunter2hunter2abc9\"".toByteArray())
        assertThat(findings).containsExactly(
            SecretFinding("app.py", SecretSeverity.WARN, "generic_assignment")
        )
    }

    @Test
    fun `short or identifier-only assignments are not warned about`() {
        assertThat(ruleIds("a.py", "password = \"hunter2\"")).isEmpty()
        assertThat(ruleIds("b.js", "const token = getTokenFromStorage();")).isEmpty()
    }

    @Test
    fun `placeholder values are not warned about`() {
        assertThat(ruleIds("a.env.md", "api_key = YOUR_API_KEY_HERE_1234")).isEmpty()
    }

    // ---- content: when content is not scanned

    @Test
    fun `null content runs only the name rules`() {
        assertThat(ruleIds(".env", content = null)).containsExactly("env_file")
        assertThat(ruleIds("app.js", content = null)).isEmpty()
    }

    @Test
    fun `binary content is never scanned for text rules`() {
        val bytes = byteArrayOf(0x00, 0x01) + "-----BEGIN PRIVATE KEY-----".toByteArray()
        assertThat(SecretScanner.scan("blob.bin", bytes)).isEmpty()
    }

    @Test
    fun `content above the size limit is not scanned`() {
        val big = "-----BEGIN PRIVATE KEY-----".toByteArray() +
            ByteArray(SecretScanner.MAX_CONTENT_BYTES) { 'a'.code.toByte() }
        assertThat(SecretScanner.scan("big.txt", big)).isEmpty()
    }

    @Test
    fun `a file can carry a name warning and a content block together`() {
        val content = "-----BEGIN OPENSSH PRIVATE KEY-----".toByteArray()
        val findings = SecretScanner.scan("deploy.pem", content)
        assertThat(findings.map { it.ruleId }).containsExactly("key_file", "private_key").inOrder()
    }
}
