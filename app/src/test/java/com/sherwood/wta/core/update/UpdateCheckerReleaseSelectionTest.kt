package com.sherwood.wta.core.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerReleaseSelectionTest {

    private fun release(vararg assetNames: String): JSONObject {
        val assets = JSONArray()
        assetNames.forEach { name ->
            assets.put(
                JSONObject()
                    .put("name", name)
                    .put("browser_download_url", "https://example.invalid/$name")
                    .put("size", 1234L)
            )
        }
        return JSONObject().put("tag_name", "v2.8.2").put("assets", assets)
    }

    @Test
    fun `picks the APK with the highest version and ignores the AAB`() {
        val release = release(
            "Sherwood-WTA-2.8.1.apk",
            "Sherwood-WTA-2.8.2.apk",
            "Sherwood-WTA-2.8.2-gplay.aab",
            "checksums.txt"
        )
        val picked = UpdateChecker.pickBestApkAsset(release)
        assertThat(picked?.optString("name")).isEqualTo("Sherwood-WTA-2.8.2.apk")
    }

    @Test
    fun `returns null when the release has no APK asset`() {
        assertThat(UpdateChecker.pickBestApkAsset(release("Sherwood-WTA-2.8.2.aab"))).isNull()
        assertThat(UpdateChecker.pickBestApkAsset(JSONObject().put("tag_name", "v1"))).isNull()
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
}
