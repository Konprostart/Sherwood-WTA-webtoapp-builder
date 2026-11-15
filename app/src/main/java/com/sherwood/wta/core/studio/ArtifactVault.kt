package com.sherwood.wta.core.studio

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sherwood.wta.core.i18n.Strings
import com.sherwood.wta.core.logging.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class ArtifactKind { APK, AAB, UNKNOWN }

/**
 * Where an artifact came from. Every field is optional except the repository, so a
 * local build (`local://`) and an Actions artifact can live in the same vault.
 */
data class ArtifactSource(
    val owner: String = "",
    val repo: String = "",
    val branch: String? = null,
    val commitSha: String? = null,
    val workflowName: String? = null,
    val runId: Long? = null,
    val runNumber: Int? = null,
    /** Set when the file came from a published release instead of an Actions artifact. */
    val releaseTag: String? = null
) {
    val display: String
        get() {
            if (owner.isNotBlank() && repo.isNotBlank()) return "$owner/$repo"
            runNumber?.let { return Strings.studioRunNumber(it) }
            runId?.let { return Strings.studioRunId(it) }
            return Strings.studioLocalSource
        }
}

/** One stored APK/AAB with everything needed to identify it later. */
data class VaultArtifact(
    val id: String,
    val fileName: String,
    val kind: String,
    val sizeBytes: Long,
    val sha256: String,
    val sourceOwner: String? = null,
    val sourceRepo: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val workflowName: String? = null,
    val runId: Long? = null,
    val runNumber: Int? = null,
    val releaseTag: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    /** SHA-256 of the signing certificate, when the APK signature verified. */
    val signerSha256: String? = null,
    val signatureVerified: Boolean = false,
    val signatureScheme: String? = null,
    /** SHA-256 of the unsigned source when this record was produced by Signing Studio. */
    val signedFromSha256: String? = null
) {
    val kindEnum: ArtifactKind
        get() = when (kind) {
            "APK" -> ArtifactKind.APK
            "AAB" -> ArtifactKind.AAB
            else -> ArtifactKind.UNKNOWN
        }
}

/** Signature status of one file, computed on demand (never trusted from the index alone). */
data class ArtifactSignature(
    val verified: Boolean,
    val scheme: String?,
    val signerCertificates: List<String>,
    val errors: List<String>
)

data class VaultVerification(
    val sha256Matches: Boolean,
    val storedSha256: String,
    val actualSha256: String,
    val signature: ArtifactSignature?
)

/**
 * Local APK/AAB vault.
 *
 * - Files live under the app's external files directory when it exists, otherwise
 *   under internal storage, so they survive cache eviction.
 * - `index.json` is written atomically (temp + rename) and is only a cache: every
 *   verification recomputes the SHA-256 from the file itself.
 * - Nothing is installed or signed here; [SigningStudio] and the install helper do
 *   that explicitly, after the user confirms.
 */
class ArtifactVault private constructor(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val vaultDir: File by lazy {
        val external = context.getExternalFilesDir(null)
        val base = if (external != null) {
            File(external, DIR_NAME)
        } else {
            File(context.filesDir, DIR_NAME)
        }
        base.mkdirs()
        base
    }

    private val indexFile: File get() = File(vaultDir, INDEX_FILE)

    fun directory(): File = vaultDir

    fun fileFor(artifact: VaultArtifact): File = File(vaultDir, artifact.fileName)

    @Synchronized
    fun list(): List<VaultArtifact> {
        val primary = readIndex(indexFile)
        if (primary != null) return primary
        if (!indexFile.isFile) return emptyList()
        // A truncated index (power loss mid-write on a device without fsync) must not
        // silently present an empty vault; the previous index is kept as `.bak`.
        val backup = readIndex(File(vaultDir, "$INDEX_FILE.bak"))
        if (backup != null) {
            AppLogger.w(TAG, "Vault index was unreadable; recovered from the backup")
            return backup
        }
        AppLogger.w(TAG, "Could not read the vault index")
        return emptyList()
    }

    private fun readIndex(file: File): List<VaultArtifact>? {
        if (!file.isFile) return null
        return runCatching {
            val index = gson.fromJson(file.readText(Charsets.UTF_8), VaultIndex::class.java)
            index?.artifacts
                ?.filter { it.sha256.length == 64 }
                ?.sortedByDescending { it.savedAtEpochMs }
        }.getOrNull()
    }

    /**
     * Copies [source] into the vault, computes its hash and version metadata, and
     * returns the record. A duplicate (same SHA-256) reuses the existing record so
     * repeated downloads do not fill storage.
     */
    @Synchronized
    fun importFile(
        source: File,
        originalName: String,
        artifactSource: ArtifactSource = ArtifactSource()
    ): Result<VaultArtifact> {
        if (!source.isFile || source.length() == 0L) {
            return Result.failure(IllegalArgumentException("Artifact file is missing or empty"))
        }
        return runCatching {
            val bytesHash = sha256(source)
            val existing = list().firstOrNull { it.sha256 == bytesHash }
            if (existing != null) {
                // Same bytes, new provenance: keep the file, refresh where it came from.
                // Returning the stale record would silently attribute a rebuild to the
                // previous run.
                val sameOrigin = existing.sourceOwner == artifactSource.owner &&
                    existing.sourceRepo == artifactSource.repo &&
                    existing.runId == artifactSource.runId &&
                    existing.releaseTag == artifactSource.releaseTag
                if (fileFor(existing).isFile && sameOrigin) return@runCatching existing
                val refreshed = existing.copy(
                    sourceOwner = artifactSource.owner.ifBlank { existing.sourceOwner },
                    sourceRepo = artifactSource.repo.ifBlank { existing.sourceRepo },
                    branch = artifactSource.branch ?: existing.branch,
                    commitSha = artifactSource.commitSha ?: existing.commitSha,
                    workflowName = artifactSource.workflowName ?: existing.workflowName,
                    runId = artifactSource.runId ?: existing.runId,
                    runNumber = artifactSource.runNumber ?: existing.runNumber,
                    releaseTag = artifactSource.releaseTag ?: existing.releaseTag
                )
                if (fileFor(existing).isFile) {
                    replace(refreshed)
                    return@runCatching refreshed
                }
                // The index knows about a file that is gone; drop the record and re-import.
                list().filterNot { it.id == existing.id }.let { writeIndex(it) }
            }
            // A copy that cannot fit is refused before it fills the device: vault files
            // are kept forever until the user deletes them.
            if (vaultDir.usableSpace in 1L until source.length()) {
                throw java.io.IOException("Not enough free storage for ${source.length()} bytes")
            }
            val safeName = sanitizeFileName(originalName)
            val kind = detectKind(safeName)
            val id = "${bytesHash.take(12)}-${System.currentTimeMillis()}"
            val target = uniqueTarget(safeName, id)
            // Write to a sibling and rename: an interrupted copy must not leave a partial
            // file that looks like a stored artifact but has no index record.
            val partial = File(vaultDir, target.name + ".part")
            try {
                source.copyTo(partial, overwrite = true)
                if (partial.length() != source.length()) {
                    throw java.io.IOException("Copy of ${source.name} was incomplete")
                }
                if (!partial.renameTo(target)) {
                    throw java.io.IOException("Could not move ${source.name} into the vault")
                }
            } catch (e: Exception) {
                partial.delete()
                target.delete()
                throw e
            }
            val packageInfo = readPackageInfo(target, kind)
            val signature = readSignature(target, kind)
            val record = VaultArtifact(
                id = id,
                fileName = target.name,
                kind = kind.name,
                sizeBytes = target.length(),
                sha256 = bytesHash,
                sourceOwner = artifactSource.owner.ifBlank { null },
                sourceRepo = artifactSource.repo.ifBlank { null },
                branch = artifactSource.branch,
                commitSha = artifactSource.commitSha,
                workflowName = artifactSource.workflowName,
                runId = artifactSource.runId,
                runNumber = artifactSource.runNumber,
                releaseTag = artifactSource.releaseTag,
                packageName = packageInfo?.packageName,
                versionName = packageInfo?.versionName,
                versionCode = packageInfo?.versionCode,
                signerSha256 = signature?.signerCertificates?.firstOrNull(),
                signatureVerified = signature?.verified == true,
                signatureScheme = signature?.scheme
            )
            try {
                writeIndex(list() + record)
            } catch (e: Exception) {
                // Never leave a file the index does not know about.
                target.delete()
                throw e
            }
            record
        }
    }

    /** Imports in-memory bytes, e.g. an unzipped artifact member. */
    fun importBytes(
        bytes: ByteArray,
        originalName: String,
        artifactSource: ArtifactSource = ArtifactSource()
    ): Result<VaultArtifact> {
        val temp = File(vaultDir, ".import-${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(temp).use { it.write(bytes) }
            importFile(temp, originalName, artifactSource)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            temp.delete()
        }
    }

    /** Recomputes the SHA-256 and re-verifies the APK/AAB signature from disk. */
    fun verify(artifact: VaultArtifact): VaultVerification {
        val file = fileFor(artifact)
        if (!file.isFile) {
            return VaultVerification(false, artifact.sha256, "", null)
        }
        val actual = runCatching { sha256(file) }.getOrElse { "" }
        val signature = readSignature(file, artifact.kindEnum)
        return VaultVerification(
            sha256Matches = actual.isNotEmpty() && actual == artifact.sha256,
            storedSha256 = artifact.sha256,
            actualSha256 = actual,
            signature = signature
        )
    }

    @Synchronized
    fun delete(artifact: VaultArtifact): Boolean {
        val removed = fileFor(artifact).delete()
        writeIndex(list().filterNot { it.id == artifact.id })
        return removed
    }

    @Synchronized
    fun replace(record: VaultArtifact) {
        writeIndex(list().map { if (it.id == record.id) record else it })
    }

    fun readSignature(file: File): ArtifactSignature = signatureOf(file)

    /** Signature status for a known artifact kind; an AAB is a JAR, not an APK. */
    fun readSignature(file: File, kind: ArtifactKind): ArtifactSignature = when (kind) {
        ArtifactKind.APK -> signatureOf(file)
        ArtifactKind.AAB -> aabSignatureOf(file)
        ArtifactKind.UNKNOWN -> ArtifactSignature(false, null, emptyList(), listOf("Unknown artifact kind"))
    }

    /** APK manifest metadata without installing the package. */
    fun readApkInfo(file: File): ApkInfo? = apkInfoOf(context, file)

    /**
     * Package identity for either artifact kind: `PackageManager` for an APK, the
     * protobuf manifest for an AAB. Returns null when the file cannot be parsed.
     */
    fun readPackageInfo(file: File, kind: ArtifactKind): ApkInfo? = when (kind) {
        ArtifactKind.APK -> apkInfoOf(context, file)
        ArtifactKind.AAB -> AabManifestInfo.read(file)?.let { info ->
            ApkInfo(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = info.versionCode?.toInt(),
                minSdk = info.minSdk,
                targetSdk = info.targetSdk
            )
        }
        ArtifactKind.UNKNOWN -> null
    }

    private fun uniqueTarget(safeName: String, id: String): File {
        val base = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var candidate = File(vaultDir, "$base-$id$suffix")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(vaultDir, "$base-$id-${counter++}$suffix")
        }
        return candidate
    }

    /**
     * Persists the index, throwing on failure.
     *
     * Swallowing the error used to make [importFile] report success for a record that was
     * never persisted — an invisible file that no screen could show or delete. The old
     * index is moved to `.bak` rather than deleted first, so a crash during the rename
     * leaves something to recover from.
     */
    private fun writeIndex(artifacts: List<VaultArtifact>) {
        val temp = File(vaultDir, "$INDEX_FILE.tmp")
        try {
            temp.writeText(
                gson.toJson(VaultIndex(artifacts.sortedByDescending { it.savedAtEpochMs })),
                Charsets.UTF_8
            )
            if (!temp.renameTo(indexFile)) {
                val backup = File(vaultDir, "$INDEX_FILE.bak")
                if (indexFile.isFile) {
                    backup.delete()
                    check(indexFile.renameTo(backup)) { "Could not move the vault index aside" }
                }
                if (!temp.renameTo(indexFile)) {
                    // Put the old index back rather than leaving the vault with none.
                    if (backup.isFile) backup.renameTo(indexFile)
                    throw java.io.IOException("Could not write the vault index")
                }
                backup.delete()
            }
        } finally {
            temp.delete()
        }
    }

    private data class VaultIndex(val artifacts: List<VaultArtifact> = emptyList())

    companion object {
        private const val TAG = "ArtifactVault"
        private const val DIR_NAME = "studio/vault"
        private const val INDEX_FILE = "index.json"

        @Volatile
        private var INSTANCE: ArtifactVault? = null

        fun getInstance(context: Context): ArtifactVault =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ArtifactVault(context.applicationContext).also { INSTANCE = it }
            }

        private val EXTENSION_RE = Regex("[^A-Za-z0-9._-]")

        /** Keeps the name usable on every filesystem and prevents path traversal. */
        fun sanitizeFileName(raw: String): String {
            val withoutDirs = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = withoutDirs.replace(EXTENSION_RE, "_").trim('_', '.')
            return cleaned.ifBlank { "artifact.apk" }.take(120)
        }

        fun detectKind(fileName: String): ArtifactKind = when (fileName.substringAfterLast('.', "").lowercase()) {
            "apk" -> ArtifactKind.APK
            "aab" -> ArtifactKind.AAB
            else -> ArtifactKind.UNKNOWN
        }

        /** Sniffs the zip layout: an APK has `AndroidManifest.xml`, an AAB has `BundleConfig.pb`. */
        fun detectKindFromZip(file: File): ArtifactKind = runCatching {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                when {
                    "AndroidManifest.xml" in names -> ArtifactKind.APK
                    "BundleConfig.pb" in names || names.any { it.startsWith("base/manifest/") } ->
                        ArtifactKind.AAB
                    else -> ArtifactKind.UNKNOWN
                }
            }
        }.getOrDefault(ArtifactKind.UNKNOWN)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun ByteArray.toHex(): String =
            joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        /**
         * Verifies the APK signature with the same apksig library the export pipeline
         * uses. Returns the certificate hashes so a user can compare an artifact with
         * the keystore they expect.
         */
        fun signatureOf(file: File): ArtifactSignature {
            if (!file.isFile) {
                return ArtifactSignature(false, null, emptyList(), listOf("File is missing"))
            }
            return try {
                val result = com.android.apksig.ApkVerifier.Builder(file).build().verify()
                val certs = result.signerCertificates.orEmpty().map { cert ->
                    MessageDigest.getInstance("SHA-256").digest(cert.encoded).toHex()
                }
                val scheme = when {
                    result.isVerifiedUsingV3Scheme -> "v3"
                    result.isVerifiedUsingV2Scheme -> "v2"
                    result.isVerifiedUsingV1Scheme -> "v1"
                    else -> null
                }
                ArtifactSignature(
                    verified = result.isVerified,
                    scheme = scheme,
                    signerCertificates = certs,
                    errors = result.errors.map { it.toString() }
                )
            } catch (e: Exception) {
                ArtifactSignature(false, null, emptyList(), listOf(e.javaClass.simpleName))
            }
        }

        @Suppress("DEPRECATION")
        private fun apkInfoOf(context: Context, file: File): ApkInfo? = runCatching {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: return@runCatching null
            ApkInfo(
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode,
                minSdk = info.applicationInfo?.minSdkVersion,
                targetSdk = info.applicationInfo?.targetSdkVersion
            )
        }.getOrNull()

        /**
         * Verifies an AAB's JAR (v1) signature.
         *
         * apksig only understands APKs, but a bundle is a signed JAR: opening it with
         * `JarFile(verify = true)` and reading every entry recomputes each digest and
         * throws when a single byte was altered, which is exactly the integrity claim
         * the Inspector makes. Reading the entries is required — `JarFile` verifies
         * lazily, so the certificates are only meaningful after the stream was drained.
         */
        fun aabSignatureOf(file: File): ArtifactSignature {
            if (!file.isFile) {
                return ArtifactSignature(false, null, emptyList(), listOf("File is missing"))
            }
            return try {
                java.util.jar.JarFile(file, true).use { jar ->
                    var certificates = emptyList<String>()
                    var entryCount = 0
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        // Draining the stream is what triggers the digest check.
                        jar.getInputStream(entry).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (stream.read(buffer) > 0) Unit
                        }
                        if (certificates.isEmpty()) {
                            certificates = entry.certificates.orEmpty()
                                .mapNotNull { cert ->
                                    runCatching {
                                        MessageDigest.getInstance("SHA-256")
                                            .digest(cert.encoded)
                                            .toHex()
                                    }.getOrNull()
                                }
                            if (certificates.isNotEmpty()) certificates = certificates.distinct()
                        }
                        entryCount++
                    }
                    if (entryCount == 0) {
                        return ArtifactSignature(false, null, emptyList(), listOf("Archive is empty"))
                    }
                    ArtifactSignature(
                        verified = certificates.isNotEmpty(),
                        scheme = if (certificates.isNotEmpty()) "v1" else null,
                        signerCertificates = certificates,
                        errors = if (certificates.isEmpty()) listOf("Archive is not signed") else emptyList()
                    )
                }
            } catch (e: SecurityException) {
                ArtifactSignature(false, null, emptyList(), listOf("Signature check failed: ${e.message}"))
            } catch (e: Exception) {
                ArtifactSignature(false, null, emptyList(), listOf(e.javaClass.simpleName))
            }
        }
    }
}

data class ApkInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Int?,
    val minSdk: Int? = null,
    val targetSdk: Int? = null
)
