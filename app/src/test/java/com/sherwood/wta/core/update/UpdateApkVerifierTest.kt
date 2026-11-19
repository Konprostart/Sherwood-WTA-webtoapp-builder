package com.sherwood.wta.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateApkVerifierTest {

    private val pkg = "com.sherwood.wta"
    private val signature = "aa".repeat(32)

    private fun archive(
        packageName: String = pkg,
        versionCode: Long = 105L,
        signatureSha256: String? = signature
    ) = UpdateApkVerifier.ArchiveInfo(packageName, versionCode, signatureSha256)

    @Test
    fun `a newer build of the same signed package may be installed`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            installedSignatureSha256 = signature,
            archive = archive()
        )
        assertThat(verdict).isEqualTo(UpdateApkVerifier.Verdict.Ok)
    }

    @Test
    fun `an unknown signature does not block a legitimate update`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            installedSignatureSha256 = null,
            archive = archive(signatureSha256 = null)
        )
        assertThat(verdict).isEqualTo(UpdateApkVerifier.Verdict.Ok)
    }

    @Test
    fun `the other flavor is refused instead of installed beside the app`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            installedSignatureSha256 = signature,
            archive = archive(packageName = "com.sherwood.wta.play")
        )
        assertThat(verdict).isInstanceOf(UpdateApkVerifier.Verdict.WrongPackage::class.java)
        val wrong = verdict as UpdateApkVerifier.Verdict.WrongPackage
        assertThat(wrong.expected).isEqualTo(pkg)
        assertThat(wrong.actual).isEqualTo("com.sherwood.wta.play")
    }

    @Test
    fun `a differently signed build is refused`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            installedSignatureSha256 = signature,
            archive = archive(signatureSha256 = "bb".repeat(32))
        )
        assertThat(verdict).isEqualTo(UpdateApkVerifier.Verdict.SignatureMismatch)
    }

    @Test
    fun `an equal or older version code is not an update`() {
        val equal = UpdateApkVerifier.evaluate(pkg, 104L, signature, archive(versionCode = 104L))
        val older = UpdateApkVerifier.evaluate(pkg, 104L, signature, archive(versionCode = 103L))
        assertThat(equal).isInstanceOf(UpdateApkVerifier.Verdict.NotNewer::class.java)
        assertThat(older).isInstanceOf(UpdateApkVerifier.Verdict.NotNewer::class.java)
    }

    @Test
    fun `an unreadable archive is refused`() {
        assertThat(UpdateApkVerifier.evaluate(pkg, 104L, signature, null))
            .isEqualTo(UpdateApkVerifier.Verdict.Unreadable)
    }
}
