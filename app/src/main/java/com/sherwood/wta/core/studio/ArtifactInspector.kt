package com.sherwood.wta.core.studio

import android.content.Context
import com.sherwood.wta.core.i18n.Strings

/**
 * Reads an artifact from the vault and reports what is actually known about it.
 *
 * Every value is recomputed from the bytes on disk: the SHA-256 is not read back
 * from the index, and the signature is verified again. The report keeps four
 * independent claims apart, because none of them imply the others:
 *
 *  - `sha256Matches` — the file is exactly the file that was imported.
 *  - `signatureVerified` / `signerSha256` — who signed it, if anyone.
 *  - `packageName` … — what the manifest says it is.
 *  - `sourceDisplay` / `commitSha` / `runNumber` — where it was downloaded from.
 *
 * The `warnings` list carries relationships between those claims, e.g. "the file
 * changed since import" or "the package name does not match the recorded one".
 * A matching hash alone never produces a "trusted" verdict.
 */
class ArtifactInspector(
    private val context: Context,
    private val vault: ArtifactVault = ArtifactVault.getInstance(context)
) {

    data class Report(
        val fileName: String,
        val kind: String,
        val sizeBytes: Long,
        val sha256: String,
        val sha256Matches: Boolean,
        val signatureVerified: Boolean,
        val signatureScheme: String?,
        val signerSha256: String?,
        val signerCount: Int,
        val signatureErrors: List<String>,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Int?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val sourceDisplay: String,
        val workflowName: String?,
        val commitSha: String?,
        val runNumber: Int?,
        val signedFromSha256: String?,
        val warnings: List<String>
    )

    fun inspect(artifact: VaultArtifact): Report {
        val file = vault.fileFor(artifact)
        val exists = file.isFile
        val actualSha = if (exists) runCatching { ArtifactVault.sha256(file) }.getOrElse { "" } else ""
        val shaMatches = actualSha.isNotEmpty() && actualSha == artifact.sha256
        val signature = if (exists) vault.readSignature(file, artifact.kindEnum)
        else ArtifactSignature(false, null, emptyList(), listOf("File is missing"))
        val packageInfo = if (exists) vault.readPackageInfo(file, artifact.kindEnum) else null
        val warnings = buildList {
            if (!exists) {
                add("The artifact file is missing from the vault")
            } else if (!shaMatches) {
                add("The file content changed since it was imported")
            }
            if (!artifact.packageName.isNullOrBlank() &&
                packageInfo?.packageName != null &&
                !artifact.packageName.equals(packageInfo.packageName, ignoreCase = true)
            ) {
                add("The recorded package name does not match the manifest")
            }
            if (signature.signerCertificates.size > 1) {
                add("The artifact carries more than one signer certificate")
            }
            if (!signature.verified && signature.signerCertificates.isNotEmpty()) {
                add("A signature is present but did not verify")
            }
        }
        return Report(
            fileName = artifact.fileName,
            kind = artifact.kind,
            sizeBytes = if (exists) file.length() else artifact.sizeBytes,
            sha256 = if (actualSha.isNotEmpty()) actualSha else artifact.sha256,
            sha256Matches = shaMatches,
            signatureVerified = signature.verified,
            signatureScheme = signature.scheme,
            signerSha256 = signature.signerCertificates.firstOrNull(),
            signerCount = signature.signerCertificates.size,
            signatureErrors = signature.errors,
            packageName = packageInfo?.packageName ?: artifact.packageName,
            versionName = packageInfo?.versionName ?: artifact.versionName,
            versionCode = packageInfo?.versionCode ?: artifact.versionCode,
            minSdk = packageInfo?.minSdk,
            targetSdk = packageInfo?.targetSdk,
            sourceDisplay = artifactSourceDisplay(artifact),
            workflowName = artifact.workflowName,
            commitSha = artifact.commitSha,
            runNumber = artifact.runNumber,
            signedFromSha256 = artifact.signedFromSha256,
            warnings = warnings
        )
    }

    /**
     * Same precedence as [ArtifactSource.display]: repository first, then the run
     * number, then the raw run id, then "local". The run *id* is a Long (it will not
     * fit an Int), so it goes through `studioRunId` rather than being truncated into
     * the run-number label — a truncated id would render a number that does not exist.
     */
    private fun artifactSourceDisplay(artifact: VaultArtifact): String {
        val owner = artifact.sourceOwner
        val repo = artifact.sourceRepo
        if (!owner.isNullOrBlank() && !repo.isNullOrBlank()) {
            val branch = artifact.branch
            return if (branch.isNullOrBlank()) "$owner/$repo" else "$owner/$repo · $branch"
        }
        artifact.runNumber?.let { return Strings.studioRunNumber(it) }
        artifact.runId?.let { return Strings.studioRunId(it) }
        return Strings.studioLocalSource
    }
}
