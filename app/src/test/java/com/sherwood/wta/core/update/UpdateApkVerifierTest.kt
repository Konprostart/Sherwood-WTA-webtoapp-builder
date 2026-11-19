package com.sherwood.wta.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateApkVerifierTest {

    private val pkg = "com.sherwood.wta"

    @Test
    fun `a newer build of the same package may be installed`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            archive = UpdateApkVerifier.ArchiveInfo(pkg, 105L)
        )
        assertThat(verdict).isEqualTo(UpdateApkVerifier.Verdict.Ok)
    }

    @Test
    fun `the other flavor is refused instead of installed beside the app`() {
        val verdict = UpdateApkVerifier.evaluate(
            expectedPackage = pkg,
            installedVersionCode = 104L,
            archive = UpdateApkVerifier.ArchiveInfo("com.sherwood.wta.play", 105L)
        )
        assertThat(verdict).isInstanceOf(UpdateApkVerifier.Verdict.WrongPackage::class.java)
        val wrong = verdict as UpdateApkVerifier.Verdict.WrongPackage
        assertThat(wrong.expected).isEqualTo(pkg)
        assertThat(wrong.actual).isEqualTo("com.sherwood.wta.play")
    }

    @Test
    fun `an equal or older version code is not an update`() {
        val equal = UpdateApkVerifier.evaluate(pkg, 104L, UpdateApkVerifier.ArchiveInfo(pkg, 104L))
        val older = UpdateApkVerifier.evaluate(pkg, 104L, UpdateApkVerifier.ArchiveInfo(pkg, 103L))
        assertThat(equal).isInstanceOf(UpdateApkVerifier.Verdict.NotNewer::class.java)
        assertThat(older).isInstanceOf(UpdateApkVerifier.Verdict.NotNewer::class.java)
    }

    @Test
    fun `an unreadable archive is refused`() {
        assertThat(UpdateApkVerifier.evaluate(pkg, 104L, null))
            .isEqualTo(UpdateApkVerifier.Verdict.Unreadable)
    }
}
