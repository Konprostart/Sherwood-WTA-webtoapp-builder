package com.sherwood.wta.core.workspace

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The allowlist is the rule that decides what may leave the device inside a shareable
 * archive, so it is tested path by path rather than through an export.
 *
 * Both directions matter:
 *  - a credential-shaped name must be refused *even inside the session sandbox*, where
 *    the agent could have written anything;
 *  - an ordinary project file must be admitted, or the rule would be worked around
 *    (users would stop trusting the export) and would protect nothing.
 */
@RunWith(RobolectricTestRunner::class)
class WorkspaceAllowlistTest {

    private fun admitted(path: String) = WorkspaceAllowlist.admits(path).admitted
    private fun refusal(path: String) = WorkspaceAllowlist.admits(path).refusal

    // ---------------------------------------------------------------- admitted

    @Test
    fun `ordinary project files are admitted`() {
        listOf(
            "index.html",
            "app.js",
            "styles/main.css",
            "src/components/Header.tsx",
            "README.md",
            "data/notes.txt",
            ".gitignore",
            "package.json",
            "Makefile",
            "Cargo.toml",
            "tokenizer.js",
            "author.html",
            "Screenshot_20240101.png"
        ).forEach { path ->
            assertThat(admitted(path)).isTrue()
        }
    }

    @Test
    fun `a dotted name whose last extension is plain is not key material`() {
        // `notes.pem.txt` is a text file about certificates; only the final extension
        // decides, otherwise the rule would refuse documentation.
        assertThat(admitted("docs/notes.pem.txt")).isTrue()
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `dot env files of every flavour are refused`() {
        listOf(".env", ".env.local", ".env.production", "config/.env.staging").forEach { path ->
            assertThat(refusal(path)).isEqualTo(WorkspaceAllowlist.Refusal.SECRET_NAME)
        }
    }

    @Test
    fun `key material and signing stores are refused`() {
        listOf(
            "cert.pem",
            "server.key",
            "store.p12",
            "release.pfx",
            "upload.jks",
            "app.keystore",
            "id_rsa",
            "id_ed25519.pub",
            "keys/.netrc",
            "keystore.properties",
            "credentials.json"
        ).forEach { path ->
            assertThat(admitted(path)).isFalse()
            assertThat(refusal(path)).isEqualTo(WorkspaceAllowlist.Refusal.SECRET_NAME)
        }
    }

    @Test
    fun `credential words are matched as whole tokens`() {
        listOf(
            "github_token.txt",
            "my-secret.json",
            "db_password.yaml",
            "api_credentials.md",
            "aws_apikey.env"
        ).forEach { path ->
            assertThat(refusal(path)).isEqualTo(WorkspaceAllowlist.Refusal.SECRET_NAME)
        }
        // ... and not as substrings, which is what keeps honest source files in.
        assertThat(admitted("tokenizer.js")).isTrue()
        assertThat(admitted("secretsanta.html")).isTrue()
        assertThat(admitted("passwordless-login.md")).isTrue()
    }

    @Test
    fun `repository and cache directories are refused wholesale`() {
        listOf(
            ".git/config",
            ".git/objects/ab/cdef",
            ".session/messages.json",
            ".changes/index.html",
            "node_modules/left-pad/index.js",
            "__pycache__/main.cpython-312.pyc",
            ".venv/lib/python3.12/site-packages/x.py"
        ).forEach { path ->
            assertThat(admitted(path)).isFalse()
            assertThat(refusal(path)).isEqualTo(WorkspaceAllowlist.Refusal.EXCLUDED_DIRECTORY)
        }
    }

    @Test
    fun `the git config is refused because a remote url can carry a token`() {
        // The concrete reason `.git` is on the excluded list at all.
        assertThat(admitted(".git/config")).isFalse()
    }

    @Test
    fun `traversal and absolute paths are unsafe`() {
        listOf(
            "../outside.txt",
            "a/../../b.txt",
            "/etc/passwd",
            "C:/windows/system32/config",
            "~/secrets.txt",
            ""
        ).forEach { path ->
            assertThat(admitted(path)).isFalse()
            assertThat(refusal(path)).isEqualTo(WorkspaceAllowlist.Refusal.UNSAFE_PATH)
        }
    }

    @Test
    fun `any dot dot segment is refused, matching the sandbox resolver`() {
        // ProjectFileManager.resolveSafe rejects ".." anywhere in the path; the bundle
        // applies the same rule so a path that cannot be written cannot be exported.
        assertThat(admitted("assets/..hidden/logo.png")).isFalse()
        assertThat(admitted("v1..2/notes.md")).isFalse()
    }
}
