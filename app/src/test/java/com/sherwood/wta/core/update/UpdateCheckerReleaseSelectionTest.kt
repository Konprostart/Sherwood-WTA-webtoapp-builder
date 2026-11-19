package com.sherwood.wta.core.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerReleaseSelectionTest {

    private fun release(
        tag: String = "v2.8.2",
        vararg assetNames: String,
        prerelease: Boolean = false,
        draft: Boolean = false
    ): JSONObject {
        val assets = JSONArray()
        assetNames.forEach { name ->
            assets.put(
                JSONObject()
                    .put("name", name)
                    .put("browser_download_url", "https://example.invalid/$name")
                    .put("size", 1234L)
            )
        }
        return JSONObject()
            .put("tag_name", tag)
            .put("assets", assets)
            .put("prerelease", prerelease)
            .put("draft", draft)
    }

    @Test
    fun `picks the APK with the highest version and ignores the AAB`() {
        val release = release(
            tag = "v2.8.2",
            "Sherwood-WTA-2.8.1.apk",
            "Sherwood-WTA-2.8.2.apk",
            "Sherwood-WTA-2.8.2-gplay.aab",
            "checksums.txt"
        )
        val picked = UpdateChecker.pickBestApkAsset(release, UpdateChecker.Variant.STANDARD)
        assertThat(picked?.optString("name")).isEqualTo("Sherwood-WTA-2.8.2.apk")
    }

    @Test
    fun `a gplay build is offered only the gplay asset`() {
        val release = release(
            tag = "v2.12.1",
            "Sherwood-WTA-2.12.1.apk",
            "Sherwood-WTA-2.12.1-gplay.apk",
            "Sherwood-WTA-2.12.1-gplay.aab"
        )
        val picked = UpdateChecker.pickBestApkAsset(release, UpdateChecker.Variant.GPLAY)
        assertThat(picked?.optString("name")).isEqualTo("Sherwood-WTA-2.12.1-gplay.apk")
    }

    @Test
    fun `a standard build is never offered the gplay asset`() {
        val release = release(tag = "v2.12.1", "Sherwood-WTA-2.12.1-gplay.apk")
        assertThat(UpdateChecker.pickBestApkAsset(release, UpdateChecker.Variant.STANDARD)).isNull()
    }

    @Test
    fun `a gplay build is never offered the standard asset`() {
        val release = release(tag = "v2.12.1", "Sherwood-WTA-2.12.1.apk")
        assertThat(UpdateChecker.pickBestApkAsset(release, UpdateChecker.Variant.GPLAY)).isNull()
    }

    @Test
    fun `the variant decides both the asset and the offered download`() {
        val release = release(
            tag = "v2.12.1",
            "Sherwood-WTA-2.12.1.apk",
            "Sherwood-WTA-2.12.1-gplay.apk"
        )
        val standard = UpdateChecker.releaseResult(
            release,
            UpdateChecker.Version.parse("2.12.0")!!,
            UpdateChecker.Variant.STANDARD
        ) as UpdateChecker.Result.UpdateAvailable
        val gplay = UpdateChecker.releaseResult(
            release,
            UpdateChecker.Version.parse("2.12.0")!!,
            UpdateChecker.Variant.GPLAY
        ) as UpdateChecker.Result.UpdateAvailable
        assertThat(standard.info.downloadUrl).endsWith("Sherwood-WTA-2.12.1.apk")
        assertThat(gplay.info.downloadUrl).endsWith("Sherwood-WTA-2.12.1-gplay.apk")
        assertThat(standard.info.version).isEqualTo("2.12.1")
    }

    @Test
    fun `a release without the flavor asset fails instead of offering the other one`() {
        val release = release(tag = "v2.12.1", "Sherwood-WTA-2.12.1-gplay.apk")
        val result = UpdateChecker.releaseResult(
            release,
            UpdateChecker.Version.parse("2.12.0")!!,
            UpdateChecker.Variant.STANDARD
        )
        assertThat(result).isInstanceOf(UpdateChecker.Result.Failed::class.java)
        assertThat((result as UpdateChecker.Result.Failed).message).contains("standard")
    }

    @Test
    fun `an installed version at or above the release is up to date`() {
        val release = release(tag = "v2.12.1", "Sherwood-WTA-2.12.1.apk")
        assertThat(
            UpdateChecker.releaseResult(
                release,
                UpdateChecker.Version.parse("2.12.1")!!,
                UpdateChecker.Variant.STANDARD
            )
        ).isInstanceOf(UpdateChecker.Result.UpToDate::class.java)
        assertThat(
            UpdateChecker.releaseResult(
                release,
                UpdateChecker.Version.parse("2.13.0")!!,
                UpdateChecker.Variant.STANDARD
            )
        ).isInstanceOf(UpdateChecker.Result.UpToDate::class.java)
    }

    @Test
    fun `version embedded in an asset name is read through the flavor suffix`() {
        assertThat(UpdateChecker.assetVersionOf("Sherwood-WTA-2.12.1.apk")?.toString())
            .isEqualTo("2.12.1")
        assertThat(UpdateChecker.assetVersionOf("Sherwood-WTA-2.12.1-gplay.apk")?.toString())
            .isEqualTo("2.12.1")
        assertThat(UpdateChecker.assetVersionOf("sherwood.apk")).isNull()
    }

    @Test
    fun `the fallback picks the newest published release and skips drafts and prereleases`() {
        val list = JSONArray()
            .put(release(tag = "v2.13.0", "Sherwood-WTA-2.13.0.apk", prerelease = true))
            .put(release(tag = "v2.14.0", "Sherwood-WTA-2.14.0.apk", draft = true))
            .put(release(tag = "v2.12.1", "Sherwood-WTA-2.12.1.apk"))
            .put(release(tag = "v2.10.0", "Sherwood-WTA-2.10.0.apk"))
            .toString()
        val newest = UpdateChecker.newestPublishedRelease(list)
        assertThat(newest?.optString("tag_name")).isEqualTo("v2.12.1")
    }

    @Test
    fun `the fallback ignores unparseable tags`() {
        val list = JSONArray()
            .put(release(tag = "nightly", "Sherwood-WTA-nightly.apk"))
            .put(release(tag = "v2.12.1", "Sherwood-WTA-2.12.1.apk"))
            .toString()
        assertThat(UpdateChecker.newestPublishedRelease(list)?.optString("tag_name"))
            .isEqualTo("v2.12.1")
    }

    @Test
    fun `the fallback returns null when nothing published is parseable`() {
        val list = JSONArray()
            .put(release(tag = "v2.13.0", "Sherwood-WTA-2.13.0.apk", prerelease = true))
            .toString()
        assertThat(UpdateChecker.newestPublishedRelease(list)).isNull()
        assertThat(UpdateChecker.newestPublishedRelease("not json")).isNull()
    }

    @Test
    fun `returns null when the release has no APK asset`() {
        assertThat(
            UpdateChecker.pickBestApkAsset(
                release(tag = "v2.8.2", "Sherwood-WTA-2.8.2.aab"),
                UpdateChecker.Variant.STANDARD
            )
        ).isNull()
        assertThat(
            UpdateChecker.pickBestApkAsset(
                JSONObject().put("tag_name", "v1"),
                UpdateChecker.Variant.STANDARD
            )
        ).isNull()
    }

    @Test
    fun `sha256 is read only from a sha256 digest`() {
        assertThat(UpdateChecker.sha256FromDigest("sha256:abc123")).isEqualTo("abc123")
        assertThat(UpdateChecker.sha256FromDigest("  sha256: abc123 ")).isEqualTo("abc123")
        assertThat(UpdateChecker.sha256FromDigest("md5:abc123")).isNull()
        assertThat(UpdateChecker.sha256FromDigest("sha256:")).isNull()
        assertThat(UpdateChecker.sha256FromDigest("")).isNull()
        assertThat(UpdateChecker.sha256FromDigest(null)).isNull()
    }

    @Test
    fun `version comparison orders semantic versions`() {
        val v281 = UpdateChecker.Version.parse("v2.8.1")!!
        val v282 = UpdateChecker.Version.parse("2.8.2")!!
        assertThat(v282 > v281).isTrue()
        assertThat(UpdateChecker.Version.parse("2.10.0")!! > v282).isTrue()
        assertThat(UpdateChecker.Version.parse("not-a-version")).isNull()
    }

    @Test
    fun `prerelease and build suffixes do not change the compared version`() {
        assertThat(UpdateChecker.Version.parse("v2.13.0-rc1")!!.toString()).isEqualTo("2.13.0")
        assertThat(UpdateChecker.Version.parse("2.12.1+build.9")!!.toString()).isEqualTo("2.12.1")
        assertThat(UpdateChecker.Version.parse("2.12")!!.toString()).isEqualTo("2.12.0")
    }
}
