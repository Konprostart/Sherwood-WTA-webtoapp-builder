package com.sherwood.wta.core.frontend

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Provenance of an imported repository, written next to the project files.
 *
 * [baseSha] is the commit the working copy was derived from; a later push can only
 * fast-forward while the remote branch still points at it. It is null when the
 * commit could not be resolved (offline import, rate-limited API, no token).
 */
data class RepoBridgeMetadata(
    @SerializedName("schemaVersion")
    val schemaVersion: Int = SCHEMA_VERSION,
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
    @SerializedName("importedAt")
    val importedAtEpochMs: Long = System.currentTimeMillis()
) {

    fun isValid(): Boolean {
        if (schemaVersion != SCHEMA_VERSION) return false
        if (!HOST_RE.matches(host)) return false
        if (owner.isBlank() || repo.isBlank() || ref.isBlank()) return false
        if (owner.any { it.isWhitespace() } || repo.any { it.isWhitespace() }) return false
        if (ref.any { it.isWhitespace() }) return false
        if (owner.contains('/') || repo.contains('/')) return false
        if (baseSha != null && !isSha(baseSha)) return false
        if (subPath != null) {
            if (subPath.isBlank()) return false
            if (subPath.startsWith("/") || subPath.startsWith("\\")) return false
            val parts = subPath.split('/', '\\')
            if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        }
        return true
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val HOST_GITHUB = "github.com"

        private val HOST_RE = Regex("^[A-Za-z0-9.-]+$")
        private val SHA_RE = Regex("^[0-9a-f]{40}$")

        fun isSha(value: String): Boolean = SHA_RE.matches(value)
    }
}

/**
 * Reads and writes [RepoBridgeMetadata] as `repo_bridge.json` in the project root.
 *
 * Writes go through a temporary file and a rename, so an interrupted save can never
 * leave a half-written JSON document behind. The store is deliberately free of
 * Android dependencies and is exercised by plain JVM tests.
 */
class RepoBridgeMetadataStore(private val gson: Gson = Gson()) {

    fun metadataFile(projectDir: File): File = File(projectDir, FILE_NAME)

    fun exists(projectDir: File): Boolean = metadataFile(projectDir).isFile

    fun write(projectDir: File, metadata: RepoBridgeMetadata): File {
        require(projectDir.isDirectory) { "Project directory does not exist" }
        require(metadata.isValid()) { "Repository metadata is invalid" }

        val target = metadataFile(projectDir)
        val temp = File(projectDir, "$FILE_NAME.tmp")
        temp.writeText(gson.toJson(metadata), Charsets.UTF_8)

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
        return runCatching {
            gson.fromJson(file.readText(Charsets.UTF_8), RepoBridgeMetadata::class.java)
                ?.takeIf { it.isValid() }
        }.getOrNull()
    }

    fun delete(projectDir: File): Boolean {
        val file = metadataFile(projectDir)
        return file.isFile && file.delete()
    }

    companion object {
        const val FILE_NAME = "repo_bridge.json"
    }
}
