package com.sherwood.wta.core.frontend

/**
 * How a finding is handled before a push.
 *
 * [BLOCK] matches a credential format that is almost never legitimate in source: the push
 * is refused until the file is removed or excluded. [WARN] matches something that often is
 * legitimate (a `.env` template, a key in a test fixture): the user sees it and decides.
 */
enum class SecretSeverity { BLOCK, WARN }

/**
 * One rule hit for one file. [ruleId] is the only detail that is logged or shown next to
 * the path; the matched value itself is never stored.
 */
data class SecretFinding(
    val path: String,
    val severity: SecretSeverity,
    val ruleId: String
)

/**
 * Finds files that should not reach a public repository.
 *
 * Two kinds of checks: file names (cheap, always run) and file content (only when the
 * caller passes the bytes). Content rules are narrow, anchored on formats with a fixed
 * prefix or length, so ordinary code does not trip them. The generic assignment rule is
 * the only fuzzy one and only ever warns.
 *
 * The scanner is pure Kotlin with no Android dependency, so it runs in plain JVM tests.
 */
object SecretScanner {

    /** Content is scanned in chunks of this size; chunks overlap so a rule is never split. */
    const val MAX_CONTENT_BYTES = 512 * 1024

    /** Overlap between consecutive scan chunks, larger than any rule match. */
    private const val CHUNK_OVERLAP_BYTES = 1024

    private val ALLOWED_ENV_SUFFIXES = setOf("example", "sample", "template", "dist", "defaults")

    private val PRIVATE_KEY_RE =
        Regex("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")
    private val GITHUB_TOKEN_RE = Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{82})\\b")
    private val AWS_KEY_RE = Regex("\\bAKIA[0-9A-Z]{16}\\b")
    private val GOOGLE_KEY_RE = Regex("\\bAIza[0-9A-Za-z_\\-]{35}\\b")
    private val SLACK_TOKEN_RE = Regex("\\bxox[baprs]-[0-9A-Za-z\\-]{10,}")
    private val STRIPE_LIVE_RE = Regex("\\bsk_live_[0-9a-zA-Z]{24,}")
    private val GENERIC_ASSIGNMENT_RE = Regex(
        "(?i)\\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*[\"']?([A-Za-z0-9/+_\\-]{16,})"
    )

    private val NPM_AUTH_TOKEN_RE = Regex("(?i)\\b_authToken\\s*=\\s*[^\\s]{10,}")

    /** Content rules that block the push. Order is the report order. */
    private val BLOCKING_CONTENT_RULES = listOf(
        "private_key" to PRIVATE_KEY_RE,
        "github_token" to GITHUB_TOKEN_RE,
        "aws_access_key" to AWS_KEY_RE,
        "google_api_key" to GOOGLE_KEY_RE,
        "slack_token" to SLACK_TOKEN_RE,
        "stripe_live_key" to STRIPE_LIVE_RE,
        "npm_auth_token" to NPM_AUTH_TOKEN_RE
    )

    /**
     * Scans one repo-relative [path]. Pass [content] only for files small enough to read
     * in one piece (see [MAX_CONTENT_BYTES]); pass null to run name rules only. Large
     * content is scanned in overlapping chunks, so size alone never disables the rules.
     */
    fun scan(path: String, content: ByteArray?): List<SecretFinding> {
        val findings = mutableListOf<SecretFinding>()
        scanName(path)?.let { findings += it }
        if (content != null) {
            findings += scanContentChunks(path, content)
        }
        return findings
    }

    private fun scanContentChunks(path: String, content: ByteArray): List<SecretFinding> {
        if (content.size <= MAX_CONTENT_BYTES) {
            return scanContent(path, decodeText(content))
        }
        val findings = mutableListOf<SecretFinding>()
        var start = 0
        while (start < content.size) {
            val end = minOf(content.size, start + MAX_CONTENT_BYTES)
            val chunk = content.copyOfRange(start, end)
            findings += scanContent(path, decodeText(chunk))
            if (end == content.size) break
            start = end - CHUNK_OVERLAP_BYTES
        }
        return findings.distinct()
    }

    /**
     * Decodes bytes as text for scanning. UTF-16 files (BOM or NUL-heavy, as produced by
     * some editors) are decoded as such; a stray NUL byte is stripped so a single crafted
     * zero cannot hide the rest of the file from the rules. Plain bytes are UTF-8.
     */
    private fun decodeText(bytes: ByteArray): String {
        val limit = minOf(bytes.size, 8 * 1024)
        var nulCount = 0
        for (i in 0 until limit) {
            if (bytes[i] == 0.toByte()) nulCount++
        }
        val nulHeavy = nulCount * 4 > limit
        if (nulHeavy) {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, Charsets.UTF_16BE)
            }
            var evenNuls = 0
            for (i in 0 until limit step 2) {
                if (bytes[i] == 0.toByte()) evenNuls++
            }
            var oddNuls = 0
            for (i in 1 until limit step 2) {
                if (bytes[i] == 0.toByte()) oddNuls++
            }
            // ASCII in UTF-16LE has the NULs at odd byte positions, BE at even ones.
            val charset = if (oddNuls >= evenNuls) Charsets.UTF_16LE else Charsets.UTF_16BE
            return String(bytes, charset)
        }
        if (nulCount == 0) {
            return String(bytes, Charsets.UTF_8)
        }
        val stripped = bytes.filter { it != 0.toByte() }.toByteArray()
        return String(stripped, Charsets.UTF_8)
    }

    /** Name-only rules. Matches on the last path segment, case-insensitively. */
    fun scanName(path: String): SecretFinding? {
        val name = path.substringAfterLast('/').lowercase()
        val ruleId = when {
            name == ".env" -> "env_file"
            name.startsWith(".env.") && name.removePrefix(".env.") !in ALLOWED_ENV_SUFFIXES -> "env_file"
            name == ".npmrc" || name == ".netrc" || name == ".pypirc" || name == ".git-credentials" -> "credential_file"
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") ||
                name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore") -> "key_file"
            isSshPrivateKeyName(name) -> "ssh_key"
            name.startsWith("service-account") && name.endsWith(".json") -> "service_account"
            name == "google-services.json" -> "firebase_config"
            else -> null
        } ?: return null
        return SecretFinding(path, SecretSeverity.WARN, ruleId)
    }

    private fun scanContent(path: String, text: String): List<SecretFinding> {
        val findings = mutableListOf<SecretFinding>()
        for ((ruleId, regex) in BLOCKING_CONTENT_RULES) {
            val hits = regex.findAll(text).map { it.value }.filterNot(::isPlaceholder)
            if (hits.any()) {
                findings += SecretFinding(path, SecretSeverity.BLOCK, ruleId)
            }
        }
        val generic = GENERIC_ASSIGNMENT_RE.findAll(text)
            .map { it.groupValues[1] }
            .filterNot(::isPlaceholder)
            .any { value -> value.any { it.isDigit() } && value.any { it.isLetter() } }
        if (generic) {
            findings += SecretFinding(path, SecretSeverity.WARN, "generic_assignment")
        }
        return findings
    }

    /** SSH private keys have no extension. The public half (`.pub`) is not a secret. */
    private fun isSshPrivateKeyName(name: String): Boolean =
        (name == "id_rsa" || name == "id_dsa" || name == "id_ecdsa" || name == "id_ed25519" ||
            name.startsWith("id_rsa-") || name.startsWith("id_ed25519-")) && !name.endsWith(".pub")

    /** Documentation keys (AWS's own EXAMPLE key) and obvious fillers are not findings. */
    private fun isPlaceholder(value: String): Boolean {
        val upper = value.uppercase()
        return upper.contains("EXAMPLE") || upper.contains("XXXXXXXX") || upper.contains("YOUR_")
    }
}
