package com.sherwood.wta.core.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.sherwood.wta.core.apkbuilder.JarSigner
import com.sherwood.wta.core.logging.AppLogger
import com.sherwood.wta.core.playstore.aab.AabSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Signs a vault APK or AAB with the app's current signing identity.
 *
 * The identity is the same one the export pipeline uses: an imported custom PKCS12
 * when the user supplied one, otherwise the auto-generated per-install key. The
 * signed file is added to the vault as a new record pointing back at its source
 * SHA-256, so a user can always see which unsigned bytes a signature belongs to.
 */
class SigningStudio(private val context: Context) {

    private val vault = ArtifactVault.getInstance(context)

    private val jarSigner: JarSigner by lazy { JarSigner(context) }

    fun signerSummary(): String? = runCatching { jarSigner.getCertificateInfo() }.getOrNull()

    fun signerFingerprint(): String? = runCatching {
        jarSigner.getCertificateFingerprints()?.sha256
    }.getOrNull()

    suspend fun sign(artifact: VaultArtifact): Result<VaultArtifact> = withContext(Dispatchers.IO) {
        val input = vault.fileFor(artifact)
        if (!input.isFile) {
            return@withContext Result.failure(IllegalStateException("Artifact file is missing"))
        }
        val suffix = if (artifact.kindEnum == ArtifactKind.AAB) ".aab" else ".apk"
        val base = artifact.fileName.substringBeforeLast('.')
        val output = File(context.cacheDir, "$base-signed$suffix")
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        try {
            val signed = when (artifact.kindEnum) {
                ArtifactKind.APK -> {
                    val info = vault.readApkInfo(input)
                    jarSigner.sign(
                        input,
                        output,
                        targetSdk = info?.targetSdk ?: 35,
                        minSdk = (info?.minSdk ?: 24).coerceAtLeast(23)
                    )
                }
                ArtifactKind.AAB -> AabSigner(context).sign(input, output)
                ArtifactKind.UNKNOWN -> false
            }
            if (!signed || !output.isFile || output.length() == 0L) {
                output.delete()
                return@withContext Result.failure(IllegalStateException("Signing failed"))
            }
            val imported = vault.importFile(
                source = output,
                originalName = "$base-signed$suffix",
                artifactSource = ArtifactSource(
                    owner = artifact.sourceOwner.orEmpty(),
                    repo = artifact.sourceRepo.orEmpty(),
                    branch = artifact.branch,
                    commitSha = artifact.commitSha,
                    workflowName = artifact.workflowName,
                    runId = artifact.runId,
                    runNumber = artifact.runNumber
                )
            )
            val result = imported.map { record ->
                val updated = record.copy(
                    signedFromSha256 = artifact.sha256,
                    versionName = record.versionName ?: artifact.versionName,
                    versionCode = record.versionCode ?: artifact.versionCode
                )
                vault.replace(updated)
                updated
            }
            output.delete()
            result
        } catch (e: Exception) {
            output.delete()
            AppLogger.e(TAG, "Signing artifact failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SigningStudio"
    }
}

/**
 * Opens the system package installer for a file inside the vault. The file is shared
 * through the app's [FileProvider] with a read grant; no world-readable URI is
 * produced and nothing is installed without the user confirming in the system dialog.
 */
object ArtifactInstaller {

    private const val TAG = "ArtifactInstaller"

    fun install(context: Context, file: File): Result<Unit> {
        if (!file.isFile) {
            return Result.failure(IllegalArgumentException("APK file is missing"))
        }
        if (!file.absolutePath.endsWith(".apk", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Only APK files can be installed"))
        }
        return try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not open the installer: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
