package com.sherwood.wta.core.workspace

/**
 * What a portable workspace bundle is allowed to carry.
 *
 * The bundle exists to move *one conversation and the project files it produced* to
 * another device. It is therefore the one archive in the app a user is expected to
 * hand to somebody else — mail it, drop it in a chat, upload it — and the value of
 * that archive is that holding it reveals nothing about the device it came from.
 *
 * Two independent rules make that true, and both have to hold:
 *
 *  1. **Structural.** The exporter is only ever given the *session sandbox*
 *     (`aicoding/sessions/<id>`) plus the session record. Credential stores,
 *     signing keystores, `_rt_prot` and DataStore/SharedPreferences are simply not
 *     reachable from the code path — no allowlist is consulted because no code path
 *     could produce those bytes. That is the primary defense.
 *  2. **Filtered.** Inside the sandbox itself, the agent may have written anything —
 *     a cloned `.git/config` carrying a remote URL with a token, a `.env` full of API
 *     keys, an `id_rsa` it was asked to generate. [admits] is the second line: the
 *     sandbox is still filtered entry by entry before anything is copied.
 *
 * The policy is a pure function of the relative path so it can be tested exhaustively
 * on the JVM, and so the exporter and the importer can apply the *same* rule. An
 * archive produced by an older/newer build that carries a path the current policy
 * rejects is refused at import rather than trusted.
 */
internal object WorkspaceAllowlist {

    /** Archive extension; also the suffix the SAF picker suggests. */
    const val EXTENSION = "sherwoodworkspace"

    const val MIME_TYPE = "application/zip"

    /** Hard caps: a workspace is a conversation, not a firmware image. */
    const val MAX_FILES = 5_000
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_SINGLE_FILE_BYTES = 128L * 1024 * 1024

    /**
     * Why an entry was refused. Carried back to the exporting user so a skipped file
     * is always *visible*: silently dropping half a project and calling the archive
     * complete is the failure mode that makes a transfer feature untrustworthy.
     */
    enum class Refusal {
        /** A directory the bundle never mirrors (history, VCS metadata, caches). */
        EXCLUDED_DIRECTORY,

        /** The name looks like it holds a credential. */
        SECRET_NAME,

        /** The path cannot be represented in the archive safely. */
        UNSAFE_PATH,

        /** Not a regular file (symlink, device node, …). */
        NOT_REGULAR_FILE,

        /** Too large on its own, or past the bundle budget. */
        TOO_LARGE
    }

    data class Verdict(val admitted: Boolean, val refusal: Refusal? = null) {
        companion object {
            val ADMIT = Verdict(true)
            fun refuse(reason: Refusal) = Verdict(false, reason)
        }
    }

    /**
     * Directories whose contents are never part of a portable workspace.
     *
     * `.session` is the store's own history file (the conversation travels in
     * `workspace.json`, so mirroring it again would resurrect a stale in-flight draft
     * on the other device). `.changes` is the local undo journal. `.git` is the
     * dangerous one: a repository's `config` can carry a remote URL with an embedded
     * token, and a clone carries the whole object database of whatever was cloned.
     * The rest are regenerable caches that would only bloat the transfer.
     */
    val EXCLUDED_DIRECTORIES = setOf(
        ".session",
        ".changes",
        ".git",
        ".hg",
        ".svn",
        ".gradle",
        ".idea",
        ".vscode",
        ".cache",
        "__pycache__",
        "node_modules",
        ".venv",
        "venv",
        ".next",
        ".nuxt",
        ".svelte-kit",
        ".parcel-cache",
        ".turbo"
    )

    /** Exact file names that are credentials by definition. */
    private val DENIED_FILE_NAMES = setOf(
        ".netrc",
        "_netrc",
        ".npmrc",
        ".pypirc",
        ".git-credentials",
        ".gitconfig",
        ".htpasswd",
        "credentials.json",
        "service-account.json",
        "keystore.properties",
        "secrets.properties",
        ".DS_Store"
    )

    /**
     * Suffixes that identify key material or a signing store.
     *
     * Deliberately matched on the *last* extension only: `notes.pem.txt` is a text
     * file, `server.pem` is a private key.
     */
    private val DENIED_EXTENSIONS = setOf(
        "pem", "key", "p12", "pfx", "jks", "keystore", "bks", "ppk",
        "mobileprovision", "asc", "gpg", "kdbx", "ovpn"
    )

    /** `.env` and every variant (`.env.local`, `.env.production`, …). */
    private fun isDotEnv(fileName: String): Boolean =
        fileName == ".env" || fileName.startsWith(".env.")

    /** SSH key pair names, with or without the conventional suffix. */
    private fun isSshKeyName(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        return base == "id_rsa" || base == "id_dsa" || base == "id_ecdsa" ||
            base == "id_ed25519" || base.startsWith("id_rsa_") ||
            base.startsWith("id_ed25519_")
    }

    /**
     * Words that make a file name a credential holder, matched on *whole tokens*.
     *
     * Tokenising is what keeps the rule precise: `tokenizer.js` is a source file
     * (`tokenizer` is one token, not `token`), while `my-token.txt` and
     * `github_token.json` are refused. A blanket `contains("token")` would have
     * excluded honest project files and taught users to work around the check.
     */
    private val DENIED_TOKENS = setOf(
        "token", "tokens", "secret", "secrets", "password", "passwords",
        "passwd", "passphrase", "credential", "credentials", "apikey",
        "apikeys", "privatekey", "privatekeys", "accesskey",
        "keystore", "signingkey"
    )

    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")

    /**
     * Decides whether [relativePath] (session-relative, `/`-separated) may enter the
     * bundle. The name is checked, never the content: a content scan would have to
     * read every byte of every file (breaking the streaming export) and would still
     * miss an obfuscated key.
     */
    fun admits(relativePath: String): Verdict {
        val path = relativePath.trim().trimStart('/').replace('\\', '/')
        if (path.isEmpty()) return Verdict.refuse(Refusal.UNSAFE_PATH)

        // Traversal and absolute/Windows paths can never be represented faithfully,
        // and allowing them would make the importer's writer the only defense.
        if (path.contains("..") || path.contains(':') || path.startsWith("~")) {
            return Verdict.refuse(Refusal.UNSAFE_PATH)
        }

        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return Verdict.refuse(Refusal.UNSAFE_PATH)
        if (segments.any { it in EXCLUDED_DIRECTORIES }) {
            return Verdict.refuse(Refusal.EXCLUDED_DIRECTORY)
        }

        val fileName = segments.last()
        // A dotfile that is not one of the explicitly allowed ones (e.g. `.gitignore`)
        // is not automatically a secret; only the named rules below refuse a file.
        if (isDeniedName(fileName)) return Verdict.refuse(Refusal.SECRET_NAME)

        return Verdict.ADMIT
    }

    /** True when [fileName] matches any credential rule. Exposed for tests and docs. */
    fun isDeniedName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (lower in DENIED_FILE_NAMES) return true
        if (isDotEnv(lower)) return true
        if (isSshKeyName(lower)) return true
        val extension = lower.substringAfterLast('.', "")
        if (extension.isNotEmpty() && extension in DENIED_EXTENSIONS) return true

        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        return tokens.any { it in DENIED_TOKENS }
    }
}
