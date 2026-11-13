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
 * Signs a vault APK or AAB with the **project's own** signing identity.
 *
 * This replaces the previous behaviour, which signed every artifact with the host
 * application's identity (`identity = null` in [AabSigner] / [JarSigner]). That is a
 * silent data loss: an APK signed with Sherwood's key can never be updated by the
 * project's real key, and an AAB signed with it permanently breaks the Play upload
 * path for that package.
 *
 * Rules enforced here:
 *
 *  - The package name must be known (APK manifest or AAB bundle manifest). No
 *    package name means no identity can be selected, so signing is refused.
 *  - The identity must be the one already associated with that package
 *    ([ProjectSigningIdentity]). A missing identity is never generated implicitly:
 *    [sign] takes `allowCreateIdentity`, which the UI only sets after the user
 *    confirms that a new key cannot update an already-published app.
 *  - An artifact that is already signed by a *different* certificate is refused
 *    unless `allowIdentityChange` is set, and the caller must have shown the user
 *    both fingerprints first.
 *
 * Signing produces a **new** vault record that points at the source's SHA-256, so
 * the original bytes are never overwritten and provenance stays visible.
 */
class SigningStudio(private val context: Context) {

    private val vault = ArtifactVault.getInstance(context)

    private val jarSigner: JarSigner by lazy { JarSigner(context) }

    enum class BlockReason {
        /** The artifact carries no readable package name. */
        UNKNOWN_PACKAGE,

        /** The file no longer exists in the vault. */
        MISSING_FILE,

        /** The kind is neither APK nor AAB. */
        UNSUPPORTED_KIND,

        /** The project has no signing identity yet. */
        NO_IDENTITY,

        /** The artifact is already signed by a different certificate. */
        IDENTITY_CONFLICT
    }

    /** Everything the UI needs to describe the operation before it runs. */
    data class SigningPlan(
        val packageName: String?,
        val identity: ProjectSigningIdentity.Summary?,
        val artifactSignerSha256: String?,
        val blockReason: BlockReason?
    ) {
        val canSignDirectly: Boolean
            get() = blockReason == null

        /** True when the user must confirm creating the project identity first. */
        val requiresIdentityCreation: Boolean
            get() = blockReason == BlockReason.NO_IDENTITY

        /** True when the user must confirm replacing a different signer. */
        val requiresIdentityChangeConfirmation: Boolean
            get() = blockReason == BlockReason.IDENTITY_CONFLICT
    }

    /**
     * Inspects the artifact without touching keys. Reads the artifact's existing
     * signature and the project identity for its package.
     */
    fun plan(artifact: VaultArtifact): SigningPlan {
        val kind = artifact.kindEnum
        if (kind == ArtifactKind.UNKNOWN) {
            return SigningPlan(artifact.packageName, null, null, BlockReason.UNSUPPORTED_KIND)
        }
        val file = vault.fileFor(artifact)
        if (!file.isFile) {
            return SigningPlan(artifact.packageName, null, null, BlockReason.MISSING_FILE)
        }
        val packageName = artifact.packageName ?: vault.readPackageInfo(file, kind)?.packageName
        if (packageName.isNullOrBlank()) {
            return SigningPlan(null, null, null, BlockReason.UNKNOWN_PACKAGE)
        }
        val identity = ProjectSigningIdentity.summary(context, packageName)
        val existingSignature = vault.readSignature(file, kind)
        val artifactSigner = existingSignature.signerCertificates.firstOrNull()
            ?.takeIf { existingSignature.verified }
        val conflict = identity != null && artifactSigner != null &&
            !artifactSigner.equals(identity.sha256, ignoreCase = true)
        val reason = when {
            identity == null -> BlockReason.NO_IDENTITY
            conflict -> BlockReason.IDENTITY_CONFLICT
            else -> null
        }
        return SigningPlan(
            packageName = packageName,
            identity = identity,
            artifactSignerSha256 = artifactSigner,
            blockReason = reason
        )
    }

    /**
     * Signs [artifact] with the project identity and imports the result into the vault.
     *
     * @param allowCreateIdentity generate a project identity when none exists (the UI
     *   must have shown that a new key cannot update an already-published app).
     * @param allowIdentityChange proceed even though the artifact is signed by a
     *   different certificate.
     */
    suspend fun sign(
        artifact: VaultArtifact,
        allowCreateIdentity: Boolean = false,
        allowIdentityChange: Boolean = false
    ): Result<VaultArtifact> = withContext(Dispatchers.IO) {
        val initial = plan(artifact)
        val packageName = initial.packageName
            ?: return@withContext Result.failure(
                IllegalStateException("Cannot determine the package name of ${artifact.fileName}")
            )
        if (initial.blockReason == BlockReason.UNSUPPORTED_KIND) {
            return@withContext Result.failure(IllegalStateException("Only APK and AAB files can be signed"))
        }
        if (initial.blockReason == BlockReason.MISSING_FILE) {
            return@withContext Result.failure(IllegalStateException("Artifact file is missing"))
        }

        val identity = if (initial.identity == null) {
            if (!allowCreateIdentity) {
                return@withContext Result.failure(
                    IllegalStateException("No signing identity exists for $packageName")
                )
            }
            runCatching { ProjectSigningIdentity.create(context, packageName) }
                .getOrElse { return@withContext Result.failure(it) }
        } else {
            initial.identity
        }

        if (initial.blockReason == BlockReason.IDENTITY_CONFLICT && !allowIdentityChange) {
            return@withContext Result.failure(
                IllegalStateException("Artifact is signed by a different certificate")
            )
        }

        val signingIdentity = ProjectSigningIdentity.identity(context, packageName)
            ?: return@withContext Result.failure(
                IllegalStateException("Signing identity for $packageName could not be loaded")
            )

        val input = vault.fileFor(artifact)
        val suffix = if (artifact.kindEnum == ArtifactKind.AAB) ".aab" else ".apk"
        val base = artifact.fileName.substringBeforeLast('.')
        val output = File(context.cacheDir, "$base-signed$suffix")
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        try {
            val signed = when (artifact.kindEnum) {
                ArtifactKind.APK -> {
                    val info = vault.readPackageInfo(input, ArtifactKind.APK)
                    jarSigner.sign(
                        input,
                        output,
                        targetSdk = info?.targetSdk ?: 35,
                        identity = signingIdentity,
                        minSdk = (info?.minSdk ?: 24).coerceAtLeast(23)
                    )
                }
                ArtifactKind.AAB -> AabSigner(context).sign(input, output, signingIdentity)
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
            // The imported record's signature fields were measured from the bytes that
            // were just written (`ArtifactVault.importFile` re-reads the file). Copying
            // the signer's return value into the record instead would let an artifact
            // that failed verification enter the vault marked "verified", which is the
            // one claim the Inspector exists to make trustworthy.
            val result = imported.flatMap { record ->
                val measuredSigner = record.signerSha256
                if (!record.signatureVerified || measuredSigner == null) {
                    vault.delete(record)
                    Result.failure(
                        IllegalStateException("The signed file failed signature verification")
                    )
                } else if (!measuredSigner.equals(identity.sha256, ignoreCase = true)) {
                    vault.delete(record)
                    Result.failure(
                        IllegalStateException(
                            "The signed file carries an unexpected signer certificate"
                        )
                    )
                } else {
                    val updated = record.copy(
                        signedFromSha256 = artifact.sha256,
                        versionName = record.versionName ?: artifact.versionName,
                        versionCode = record.versionCode ?: artifact.versionCode,
                        packageName = record.packageName ?: packageName
                    )
                    vault.replace(updated)
                    Result.success(updated)
                }
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
