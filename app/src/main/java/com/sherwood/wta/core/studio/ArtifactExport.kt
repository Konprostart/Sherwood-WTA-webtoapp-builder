package com.sherwood.wta.core.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sherwood.wta.core.logging.AppLogger
import java.io.File

/**
 * Hands a vault file to the rest of the phone.
 *
 * Two paths, both explicit:
 * - **Share** sends the file through the app's [FileProvider] with a read grant, so
 *   another app (Drive, mail, a messenger) receives a content URI and never a
 *   file path. Nothing becomes world-readable.
 * - **Save** copies the bytes into a document the user picked in the system picker
 *   (`ActivityResultContracts.CreateDocument`), which is how an AAB reaches the
 *   Downloads folder for a Play Console upload.
 *
 * The vault stores files as `<name>-<id>.<ext>`; [exportName] strips that suffix so
 * the user sees the artifact's real name in Downloads, not an internal one.
 */
object ArtifactExport {

    const val APK_MIME = "application/vnd.android.package-archive"

    /** AABs are Play upload bundles, not installable packages. */
    const val BUNDLE_MIME = "application/octet-stream"

    private const val TAG = "ArtifactExport"

    fun mimeType(artifact: VaultArtifact): String = when (artifact.kindEnum) {
        ArtifactKind.APK -> APK_MIME
        ArtifactKind.AAB -> BUNDLE_MIME
        ArtifactKind.UNKNOWN -> BUNDLE_MIME
    }

    /** `app-standard-debug-1a2b3c4d5e6f-1700000000000.apk` → `app-standard-debug.apk`. */
    fun exportName(artifact: VaultArtifact): String {
        val fileName = artifact.fileName
        val extension = fileName.substringAfterLast('.', "")
        val stem = fileName.substringBeforeLast('.', fileName)
        val clean = stem.removeSuffix("-${artifact.id}").ifBlank { stem }
        val name = if (extension.isEmpty()) clean else "$clean.$extension"
        return ArtifactVault.sanitizeFileName(name)
    }

    /** Shares [file] as a content URI; returns a failure instead of throwing. */
    fun share(context: Context, artifact: VaultArtifact, file: File): Result<Unit> {
        if (!file.isFile) {
            return Result.failure(IllegalStateException("The artifact file is missing"))
        }
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType(artifact)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, exportName(artifact)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not share the artifact: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /**
     * Copies [file] into [target] (a `content://` document chosen by the user).
     *
     * The copy is streamed rather than held in memory: an AAB can be well over
     * 100 MB and the phone must not allocate that twice.
     */
    fun copyTo(context: Context, file: File, target: Uri): Result<Long> {
        if (!file.isFile) {
            return Result.failure(IllegalStateException("The artifact file is missing"))
        }
        return try {
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: return Result.failure(IllegalStateException("Could not open the destination"))
            val written = output.use { sink ->
                file.inputStream().use { input -> input.copyTo(sink) }
            }
            Result.success(written)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not export the artifact: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
