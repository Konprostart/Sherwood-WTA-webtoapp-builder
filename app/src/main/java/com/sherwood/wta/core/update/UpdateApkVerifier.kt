package com.sherwood.wta.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sherwood.wta.core.logging.AppLogger
import java.io.File
import java.security.MessageDigest

/**
 * Last line of defence before the system installer is launched for a downloaded update.
 *
 * The download is already SHA-256 verified against the release asset and the check only offers
 * the flavor's own asset family, but neither of those facts is about *this* installation: the
 * user may have moved the file between flavors, the release workflow may have uploaded the wrong
 * variant under the right name, or this build may have been signed by someone else (an F-Droid
 * or self-built APK). Installing a mismatch would either fail with an opaque package-installer
 * error or, worse, install a second copy of the app — the two flavors have different application
 * ids.
 *
 * [evaluate] is pure so the rules are tested without a device. The signature comparison only
 * rejects a *known* mismatch: when either side cannot be read, the package installer remains the
 * authority instead of the app blocking a legitimate update.
 */
object UpdateApkVerifier {

    private const val TAG = "UpdateApkVerifier"

    /** Identity read from a downloaded APK archive. */
    data class ArchiveInfo(
        val packageName: String,
        val versionCode: Long,
        /** SHA-256 of the first signing certificate, or null when it could not be read. */
        val signatureSha256: String?
    )

    sealed interface Verdict {
        /** The archive is this app, signed like this app, and newer than the installed build. */
        data object Ok : Verdict

        /** The archive belongs to another package (typically the other flavor). */
        data class WrongPackage(val expected: String, val actual: String) : Verdict

        /** The archive is signed with a different key, so Android would refuse the upgrade. */
        data object SignatureMismatch : Verdict

        /** The archive does not advance the installed version code. */
        data class NotNewer(val actualVersionCode: Long, val installedVersionCode: Long) : Verdict

        /** The archive could not be parsed at all. */
        data object Unreadable : Verdict
    }

    fun evaluate(
        expectedPackage: String,
        installedVersionCode: Long,
        installedSignatureSha256: String?,
        archive: ArchiveInfo?
    ): Verdict {
        if (archive == null) return Verdict.Unreadable
        if (archive.packageName != expectedPackage) {
            return Verdict.WrongPackage(expectedPackage, archive.packageName)
        }
        if (installedSignatureSha256 != null &&
            archive.signatureSha256 != null &&
            installedSignatureSha256 != archive.signatureSha256
        ) {
            return Verdict.SignatureMismatch
        }
        if (archive.versionCode <= installedVersionCode) {
            return Verdict.NotNewer(archive.versionCode, installedVersionCode)
        }
        return Verdict.Ok
    }

    /** Reads the package name, version code and signer of [apkFile], or null if it is not an APK. */
    fun inspect(context: Context, apkFile: File): ArchiveInfo? = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager
            .getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        info?.let {
            ArchiveInfo(
                packageName = it.packageName,
                versionCode = versionCodeOf(it),
                signatureSha256 = signatureDigest(it)
            )
        }
    } catch (e: Exception) {
        AppLogger.w(TAG, "Could not read downloaded APK: ${e.message}")
        null
    }

    /** Verifies [apkFile] against the running app; [Ok] when the installer may be launched. */
    fun verify(context: Context, apkFile: File, installedVersionCode: Long): Verdict =
        evaluate(
            expectedPackage = context.packageName,
            installedVersionCode = installedVersionCode,
            installedSignatureSha256 = installedSignatureDigest(context),
            archive = inspect(context, apkFile)
        )

    @Suppress("DEPRECATION")
    private fun installedSignatureDigest(context: Context): String? = try {
        signatureDigest(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES))
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun signatureDigest(info: android.content.pm.PackageInfo): String? = try {
        val signature = info.signatures?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
