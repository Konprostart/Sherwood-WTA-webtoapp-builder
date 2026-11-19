package com.sherwood.wta.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sherwood.wta.core.logging.AppLogger
import java.io.File

/**
 * Last line of defence before the system installer is launched for a downloaded update.
 *
 * The download is already SHA-256 verified against the release asset and the check only offers
 * the flavor's own asset family, but neither of those facts is about *this* installation: the
 * user may have moved the file between flavors, or the release workflow may have uploaded the
 * wrong variant under the right name. Installing a mismatch would either fail with an opaque
 * package-installer error or, worse, install a second copy of the app (the two flavors have
 * different application ids).
 *
 * [evaluate] is pure so the rules are tested without a device.
 */
object UpdateApkVerifier {

    private const val TAG = "UpdateApkVerifier"

    /** Identity read from a downloaded APK archive. */
    data class ArchiveInfo(val packageName: String, val versionCode: Long)

    sealed interface Verdict {
        /** The archive is this app and is newer than the installed build. */
        data object Ok : Verdict

        /** The archive belongs to another package (typically the other flavor). */
        data class WrongPackage(val expected: String, val actual: String) : Verdict

        /** The archive does not advance the installed version code. */
        data class NotNewer(val actualVersionCode: Long, val installedVersionCode: Long) : Verdict

        /** The archive could not be parsed at all. */
        data object Unreadable : Verdict
    }

    fun evaluate(
        expectedPackage: String,
        installedVersionCode: Long,
        archive: ArchiveInfo?
    ): Verdict {
        if (archive == null) return Verdict.Unreadable
        if (archive.packageName != expectedPackage) {
            return Verdict.WrongPackage(expectedPackage, archive.packageName)
        }
        if (archive.versionCode <= installedVersionCode) {
            return Verdict.NotNewer(archive.versionCode, installedVersionCode)
        }
        return Verdict.Ok
    }

    /** Reads the package name and version code embedded in [apkFile], or null if it is not an APK. */
    fun inspect(context: Context, apkFile: File): ArchiveInfo? = try {
        val info = context.packageManager
            .getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        info?.let {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
            ArchiveInfo(packageName = it.packageName, versionCode = code)
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
            archive = inspect(context, apkFile)
        )
}
