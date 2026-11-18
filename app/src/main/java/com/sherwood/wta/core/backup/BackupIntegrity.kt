package com.sherwood.wta.core.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sherwood.wta.data.converter.Converters
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Per-entry fingerprint of everything a backup archive carries.
 *
 * The pair (size, sha256) is what turns "the file is there" into "the file is the
 * one that was written". A truncated or partially overwritten entry keeps its name
 * and may even keep its length, so length alone can never prove an archive is
 * intact — only the digest can.
 */
internal data class BackupEntryFingerprint(
    val path: String,
    val size: Long,
    val sha256: String
)

/**
 * `manifest.json` inside a v6+ archive: the complete list of entries the exporter
 * wrote, with their sizes and digests.
 *
 * The manifest is written **last**, after every other entry, because it can only be
 * emitted once all digests are known. Import therefore cannot verify entries as it
 * streams them: it stages them to temp files first and verifies the whole set
 * before a single live file is touched.
 */
internal data class BackupManifest(
    val backupVersion: Int,
    val createdAt: Long,
    val entries: List<BackupEntryFingerprint>
) {

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("backupVersion", backupVersion)
        root.addProperty("createdAt", createdAt)
        val array = com.google.gson.JsonArray()
        entries.forEach { entry ->
            val item = JsonObject()
            item.addProperty("path", entry.path)
            item.addProperty("size", entry.size)
            item.addProperty("sha256", entry.sha256)
            array.add(item)
        }
        root.add("entries", array)
        return Converters.gson.toJson(root)
    }

    companion object {
        /** Manifest schema. Bumped only for an incompatible manifest layout. */
        const val FORMAT = 1

        /**
         * Parses a manifest, throwing [BackupIntegrityException.Malformed] on anything
         * that is not a usable manifest. A manifest we cannot read is not "empty" —
         * treating it as empty would silently accept an unverified archive.
         */
        fun fromJson(json: String): BackupManifest {
            val root = try {
                JsonParser.parseString(json).asJsonObject
            } catch (e: Exception) {
                throw BackupIntegrityException.Malformed("manifest.json is not a JSON object")
            }

            val format = root.get("format")?.asInt ?: 0
            if (format != FORMAT) {
                throw BackupIntegrityException.Malformed("manifest.json format $format is not supported")
            }

            val entriesArray = try {
                root.getAsJsonArray("entries")
            } catch (e: Exception) {
                throw BackupIntegrityException.Malformed("manifest.json has no entries array")
            } ?: throw BackupIntegrityException.Malformed("manifest.json has no entries array")

            val entries = entriesArray.mapNotNull { element ->
                runCatching {
                    val item = element.asJsonObject
                    val path = item.get("path")?.asString
                    val sha = item.get("sha256")?.asString
                    val size = item.get("size")?.asLong
                    if (path.isNullOrBlank() || sha.isNullOrBlank() || size == null) null
                    else BackupEntryFingerprint(path, size, sha.lowercase())
                }.getOrNull()
            }

            if (entries.size != entriesArray.size()) {
                throw BackupIntegrityException.Malformed("manifest.json has malformed entries")
            }

            return BackupManifest(
                backupVersion = root.get("backupVersion")?.asInt ?: 0,
                createdAt = root.get("createdAt")?.asLong ?: 0L,
                entries = entries
            )
        }
    }
}

/**
 * Ways an archive can fail the integrity gate. Each carries the offending entry so
 * the failure names the file instead of reporting a vague "corrupt backup".
 */
internal sealed class BackupIntegrityException(message: String) : Exception(message) {

    /** v6+ archive that arrived without its manifest. */
    class Missing : BackupIntegrityException("Backup archive has no integrity manifest")

    /** Manifest present but unreadable. */
    class Malformed(detail: String) : BackupIntegrityException(detail)

    /** Manifest lists an entry the archive does not contain (truncated archive). */
    class Incomplete(path: String) :
        BackupIntegrityException("Backup archive is missing an entry listed in its manifest: $path")

    /** Entry bytes do not match the digest recorded at export. */
    class Mismatch(path: String) :
        BackupIntegrityException("Backup entry does not match its recorded checksum: $path")

    /** Entry would be restored but the manifest does not vouch for it. */
    class Undeclared(path: String) :
        BackupIntegrityException("Backup entry is not listed in its manifest: $path")
}

/**
 * The verification gate. Pure: it compares already-computed fingerprints, so the
 * rules are testable without a zip, a device or a database.
 */
internal object BackupIntegrity {

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return toHex(digest)
    }

    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /**
     * @param manifest the archive's manifest, or null when it carried none
     * @param observed every entry the importer actually staged/consumed
     * @param manifestRequired true for v6+ archives, where the manifest is mandatory
     *
     * Older archives (v1–v5) were written before manifests existed. They stay
     * restorable — refusing them would brick every backup a user already owns — but
     * the moment an archive *does* carry a manifest, it is enforced for every entry.
     */
    fun verify(
        manifest: BackupManifest?,
        observed: List<BackupEntryFingerprint>,
        manifestRequired: Boolean
    ) {
        if (manifest == null) {
            if (manifestRequired) throw BackupIntegrityException.Missing()
            return
        }

        val seen = observed.associateBy { it.path }

        // Every declared entry must be present and byte-identical. This is what
        // catches a truncated archive: the missing entries are still in the manifest.
        manifest.entries.forEach { declared ->
            val actual = seen[declared.path] ?: throw BackupIntegrityException.Incomplete(declared.path)
            if (actual.size != declared.size ||
                !actual.sha256.equals(declared.sha256, ignoreCase = true)
            ) {
                throw BackupIntegrityException.Mismatch(declared.path)
            }
        }

        // Nothing that will be restored may be absent from the manifest.
        val declaredPaths = manifest.entries.mapTo(HashSet(manifest.entries.size)) { it.path }
        observed.forEach { entry ->
            if (entry.path !in declaredPaths) throw BackupIntegrityException.Undeclared(entry.path)
        }
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Pass-through that digests the bytes it forwards, so an export computes every
 * entry's checksum in the same read that writes it — no second pass over the file.
 *
 * [close] deliberately only flushes: the wrapped stream is the archive itself and
 * closing it would end the zip after the first entry.
 */
internal class Sha256Sink(private val out: OutputStream) : OutputStream() {

    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L

    val size: Long get() = count

    override fun write(b: Int) {
        out.write(b)
        digest.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        digest.update(b, off, len)
        count += len
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        flush()
    }

    fun sha256(): String = BackupIntegrity.toHex(digest.digest())
}
