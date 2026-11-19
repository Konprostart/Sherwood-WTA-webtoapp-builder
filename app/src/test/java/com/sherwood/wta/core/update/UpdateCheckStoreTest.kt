package com.sherwood.wta.core.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckStoreTest {

    private lateinit var context: Context
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Matches UpdateCheckStore.PREFS_NAME; cleared so tests never see each other's cache.
        context.getSharedPreferences("sherwood_update_check", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun available(version: String = "2.12.1", current: String = "2.12.0") =
        UpdateChecker.Result.UpdateAvailable(
            UpdateChecker.ReleaseInfo(
                version = version,
                downloadUrl = "https://example.invalid/Sherwood-WTA-$version.apk",
                sizeBytes = 27_000_000L,
                sha256 = "abc",
                releaseNotes = "notes"
            ),
            current
        )

    @Test
    fun `an update verdict survives a new store instance`() {
        val store = UpdateCheckStore(context)
        store.recordAttempt(now)
        store.recordResult(available(), now)

        val reopened = UpdateCheckStore(context)
        val cached = reopened.cachedResult("2.12.0")
        assertThat(cached).isInstanceOf(UpdateChecker.Result.UpdateAvailable::class.java)
        assertThat((cached as UpdateChecker.Result.UpdateAvailable).info.version).isEqualTo("2.12.1")
        assertThat(reopened.snapshot().lastSuccessAtMillis).isEqualTo(now)
        assertThat(reopened.snapshot().lastAttemptAtMillis).isEqualTo(now)
    }

    @Test
    fun `an up-to-date verdict replaces the cached update`() {
        val store = UpdateCheckStore(context)
        store.recordResult(available(), now)
        store.recordResult(UpdateChecker.Result.UpToDate("2.12.0"), now + 1)

        assertThat(store.cachedResult("2.12.0"))
            .isInstanceOf(UpdateChecker.Result.UpToDate::class.java)
        assertThat(store.snapshot().availableUpdate("2.12.0")).isNull()
        assertThat(store.snapshot().latestVersion).isNull()
    }

    @Test
    fun `dismissing hides the marker for that version only`() {
        val store = UpdateCheckStore(context)
        store.recordResult(available("2.12.1"), now)
        assertThat(store.snapshot().showsUpdateIndicator("2.12.0")).isTrue()

        store.dismiss("2.12.1")
        assertThat(store.snapshot().showsUpdateIndicator("2.12.0")).isFalse()
        // The release itself is still offered in the dialog.
        assertThat(store.snapshot().availableUpdate("2.12.0")).isNotNull()

        store.recordResult(available("2.13.0"), now + 1)
        assertThat(store.snapshot().showsUpdateIndicator("2.12.0")).isTrue()
    }

    @Test
    fun `a cached offer is not shown after the app was updated`() {
        val store = UpdateCheckStore(context)
        store.recordResult(available("2.12.1"), now)

        assertThat(store.snapshot().availableUpdate("2.12.1")).isNull()
        assertThat(store.cachedResult("2.12.1")).isNull()
        // A newer installed build is likewise not a candidate for a downgrade offer.
        assertThat(store.cachedResult("2.13.0")).isNull()
    }

    @Test
    fun `a failed check keeps the previous answer`() {
        val store = UpdateCheckStore(context)
        store.recordResult(available(), now)
        store.recordAttempt(now + 5_000)
        store.recordResult(UpdateChecker.Result.Failed("network down"), now + 5_000)

        assertThat(store.cachedResult("2.12.0"))
            .isInstanceOf(UpdateChecker.Result.UpdateAvailable::class.java)
        assertThat(store.snapshot().lastSuccessAtMillis).isEqualTo(now)
        assertThat(store.snapshot().lastAttemptAtMillis).isEqualTo(now + 5_000)
    }

    @Test
    fun `automatic checks default on and the switch persists`() {
        val store = UpdateCheckStore(context)
        assertThat(store.snapshot().autoCheckEnabled).isTrue()

        store.setAutoCheckEnabled(false)
        assertThat(store.snapshot().autoCheckEnabled).isFalse()
        assertThat(UpdateCheckStore(context).snapshot().autoCheckEnabled).isFalse()
    }
}
