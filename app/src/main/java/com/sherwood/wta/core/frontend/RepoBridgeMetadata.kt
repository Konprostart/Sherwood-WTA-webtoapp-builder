package com.sherwood.wta.core.frontend

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sherwood.wta.core.studio.git.GitProviderId
import java.io.File

/**
 * Provenance of an imported repository, written next to the project files.
 *
 * [baseSha] is the commit the working copy was derived from; a later push can only
 * fast-forward while the remote branch still points at it. It is null when the
 * commit could not be resolved (offline import, rate-limited API, no token).
 *
 * [provider] names the hosting platform that owns the copy, and [host] its server. Both
 * are needed to open the copy against the right server later: a project imported from
 * `git.example.com` must keep using that server even after the user switches the active
 * provider to GitHub. A schema-1 record has no provider field; it was written only by the
 * GitHub importer, so it reads as GitHub and is upgraded on first open.
 */
data class RepoBridgeMetadata(
    @SerializedName("schemaVersion")
    val schemaVersion: Int = SCHEMA_VERSION,
    @SerializedName("provider")
    val provider: String? = null,
    @SerializedName("host")
    val host: String,
    @SerializedName("owner")
    val owner: String,
    @SerializedName("repo")
    val repo: String,
    @SerializedName("ref")
    val ref: String,
    @SerializedName("baseSha")
    val baseSha: String? = null,
    @SerializedName("subPath")
    val subPath: String? = null,
    /**
     * The commit a push is about to move the branch to. It is written before the ref
     * update and cleared once [baseSha] catches up, so a failed metadata write after a
     * successful push can be recovered on the next run.
     */
    @SerializedName("pendingBaseSha")
    val pendingBaseSha: String? = null,
    @SerializedName("importedAt")
    val importedAtEpochMs: Long = System.currentTimeMillis()
) {

    /**
     * The platform this copy belongs to. A record without the field predates
     * multi-provider support, so it can only be GitHub; an unknown value is rejected by
     * [isValid] rather than silently read as GitHub.
     */
    val providerId: GitProviderId
        get() = GitProviderId.entries.firstOrNull { it.storageId == resolvedProvider }
            ?: GitProviderId.GITHUB

    /** The stored provider, or GitHub for a schema-1 record. */
    val resolvedProvider: String
        get() = provider ?: PROVIDER_GITHUB

    /** Web base URL of the server, used to rebuild the provider for this copy. */
    val serverUrl: String
        get() = "https://$host"

    fun isValid(): Boolean {
        // Schema 1 is still readable: it is migrated on open rather than rejected.
        if (schemaVersion != SCHEMA_VERSION && schemaVersion != LEGACY_SCHEMA_VERSION) return false
        if (!PROVIDERS.contains(resolvedProvider)) return false
        if (!HOST_RE.matches(host)) return false
        if (owner.isBlank() || repo.isBlank() || ref.isBlank()) return false
        if (owner.any { it.isWhitespace() } || repo.any { it.isWhitespace() }) return false
        if (ref.any { it.isWhitespace() }) return false
        if (owner.contains('/') || repo.contains('/')) return false
        if (baseSha != null && !isSha(baseSha)) return false
        if (pendingBaseSha != null && !isSha(pendingBaseSha)) return false
        if (subPath != null) {
            if (subPath.isBlank()) return false
            if (subPath.startsWith("/") || subPath.startsWith("\\")) return false
            val parts = subPath.split('/', '\\')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        }
        return true
    }

    companion object {
        const val SCHEMA_VERSION = 2

        /** Written by releases before multi-provider support: GitHub only, no provider field. */
        const val LEGACY_SCHEMA_VERSION = 1

        const val PROVIDER_GITHUB = "github"
        const val HOST_GITHUB = "github.com"

        /** Every provider id a record may name; derived from the enum so the two cannot drift. */
        val PROVIDERS: Set<String> = GitProviderId.entries.map { it.storageId }.toSet()

        private val HOST_RE = Regex("^[A-Za-z0-9.-]+(:[0-9]{1,5})?$")
        private val SHA_RE = Regex("^[0-9a-f]{40}$")

        fun isSha(value: String): Boolean = SHA_RE.matches(value)

        /** True when this record predates the provider field. */
        fun isLegacy(schemaVersion: Int): Boolean = schemaVersion == LEGACY_SCHEMA_VERSION
    }
}

/**
 * Reads and writes [RepoBridgeMetadata] as `repo_bridge.json` in the project root.
 *
 * Writes go through a temporary file and a rename, so an interrupted save can never
 * leave a half-written JSON document behind. The store is deliberately free of
 * Android dependencies and is exercised by plain JVM tests.
 *
 * A schema-1 document is upgraded in place the first time it is read. That is what keeps
 * existing imported copies usable: without it, every copy imported by 2.9.6 or earlier
 * would have to be re-imported to gain a provider field.
 */
class RepoBridgeMetadataStore(private val gson: Gson = Gson()) {

    fun metadataFile(projectDir: File): File = File(projectDir, FILE_NAME)

    fun exists(projectDir: File): Boolean = metadataFile(projectDir).isFile

    fun write(projectDir: File, metadata: RepoBridgeMetadata): File {
        require(projectDir.isDirectory) { "Project directory does not exist" }
        require(metadata.isValid()) { "Repository metadata is invalid" }

        // A legacy record is upgraded on write; a caller must never persist schema 1.
        val current = metadata.copy(
            // A legacy record is upgraded on write; a caller must never persist schema 1.
            schemaVersion = RepoBridgeMetadata.SCHEMA_VERSION,
            provider = if (metadata.schemaVersion == RepoBridgeMetadata.SCHEMA_VERSION) {
                metadata.resolvedProvider
            } else {
                RepoBridgeMetadata.PROVIDER_GITHUB
            }
        )

        val target = metadataFile(projectDir)
        val temp = File(projectDir, "$FILE_NAME.tmp")
        temp.writeText(gson.toJson(current), Charsets.UTF_8)

        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IllegalStateException("Could not write $FILE_NAME")
            }
        }
        return target
    }

    fun read(projectDir: File): RepoBridgeMetadata? {
        val file = metadataFile(projectDir)
        if (!file.isFile) return null
        val parsed = runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), RepoBridgeMetadata::class.java)
                ?.takeIf { it.isValid() }
        }.getOrNull() ?: return null

        if (parsed.schemaVersion == RepoBridgeMetadata.SCHEMA_VERSION) return parsed

        // Schema 1 predates multi-provider support, so it can only be GitHub.
        val upgraded = parsed.copy(
            schemaVersion = RepoBridgeMetadata.SCHEMA_VERSION,
            provider = parsed.resolvedProvider
        )
        // Best effort: an unwritable directory still yields a usable in-memory record.
        runCatching { write(projectDir, upgraded) }
        return upgraded
    }

    fun delete(projectDir: File): Boolean {
        val file = metadataFile(projectDir)
        return file.isFile && file.delete()
    }

    companion object {
        const val FILE_NAME = "repo_bridge.json"
    }
}
