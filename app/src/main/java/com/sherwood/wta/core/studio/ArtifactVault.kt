package com.sherwood.wta.core.studio

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
    val runNumber: Int? = null
) {
    val display: String
        get() = when {
            owner.isNotBlank() && repo.isNotBlank() -> "$owner/$repo"
            runId != null -> "run #$runId"
            else -> "local"
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
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val index = gson.fromJson(indexFile.readText(Charsets.UTF_8), VaultIndex::class.java)
            index?.artifacts?.filter { it.sha256.length == 64 }?.sortedByDescending { it.savedAtEpochMs }
                ?: emptyList()
        }.getOrElse { error ->
            AppLogger.w(TAG, "Could not read vault index: ${error.javaClass.simpleName}")
            emptyList()
        }
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
            if (existing != null) return@runCatching existing
            val safeName = sanitizeFileName(originalName)
            val kind = detectKind(safeName)
            val id = "${bytesHash.take(12)}-${System.currentTimeMillis()}"
            val target = uniqueTarget(safeName, id)
            source.copyTo(target, overwrite = false)
            val apkInfo = if (kind == ArtifactKind.APK) readApkInfo(target) else null
            val signature = if (kind == ArtifactKind.APK) readSignature(target) else null
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
                packageName = apkInfo?.packageName,
                versionName = apkInfo?.versionName,
                versionCode = apkInfo?.versionCode,
                signerSha256 = signature?.signerCertificates?.firstOrNull(),
                signatureVerified = signature?.verified == true,
                signatureScheme = signature?.scheme
            )
            writeIndex(list() + record)
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

    /** Recomputes the SHA-256 and re-verifies the APK signature from disk. */
    fun verify(artifact: VaultArtifact): VaultVerification {
        val file = fileFor(artifact)
        if (!file.isFile) {
            return VaultVerification(false, artifact.sha256, "", null)
        }
        val actual = runCatching { sha256(file) }.getOrElse { "" }
        val signature = if (artifact.kindEnum == ArtifactKind.APK) readSignature(file) else null
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

    /** APK manifest metadata without installing the package. */
    fun readApkInfo(file: File): ApkInfo? = apkInfoOf(context, file)

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

    private fun writeIndex(artifacts: List<VaultArtifact>) {
        val temp = File(vaultDir, "$INDEX_FILE.tmp")
        runCatching {
            temp.writeText(gson.toJson(VaultIndex(artifacts.sortedByDescending { it.savedAtEpochMs })), Charsets.UTF_8)
            if (!temp.renameTo(indexFile)) {
                indexFile.delete()
                check(temp.renameTo(indexFile)) { "Could not write vault index" }
            }
        }.onFailure { error ->
            temp.delete()
            AppLogger.w(TAG, "Could not write vault index: ${error.javaClass.simpleName}")
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
    }
}

data class ApkInfo(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Int?,
    val minSdk: Int? = null,
    val targetSdk: Int? = null
)
