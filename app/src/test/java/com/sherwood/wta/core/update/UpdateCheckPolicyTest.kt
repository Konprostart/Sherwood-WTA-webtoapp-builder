package com.sherwood.wta.core.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateCheckPolicyTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    @Test
    fun `an automatic check is due when none ever succeeded`() {
        assertThat(UpdateCheckPolicy.isAutoCheckDue(now, lastSuccessAtMillis = 0L)).isTrue()
    }

    @Test
    fun `an automatic check waits the full interval`() {
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(now, lastSuccessAtMillis = now - 23 * hour)
        ).isFalse()
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(now, lastSuccessAtMillis = now - 23 * hour - 59 * 60_000L)
        ).isFalse()
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(now, lastSuccessAtMillis = now - 24 * hour)
        ).isTrue()
    }

    @Test
    fun `automatic checks can be switched off`() {
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(now, lastSuccessAtMillis = 0L, autoCheckEnabled = false)
        ).isFalse()
    }

    @Test
    fun `a failed automatic check is not retried on every launch`() {
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(
                now,
                lastSuccessAtMillis = 0L,
                lastAttemptAtMillis = now - 10 * 60_000L
            )
        ).isFalse()
        assertThat(
            UpdateCheckPolicy.isAutoCheckDue(
                now,
                lastSuccessAtMillis = 0L,
                lastAttemptAtMillis = now - UpdateCheckPolicy.AUTO_RETRY_FLOOR_MS - 1
            )
        ).isTrue()
    }

    @Test
    fun `a manual check is allowed when none was attempted`() {
        assertThat(UpdateCheckPolicy.isManualCheckAllowed(now, lastAttemptAtMillis = 0L)).isTrue()
    }

    @Test
    fun `a manual check inside the floor is throttled`() {
        assertThat(
            UpdateCheckPolicy.isManualCheckAllowed(
                now,
                lastAttemptAtMillis = now - UpdateCheckPolicy.MANUAL_CHECK_FLOOR_MS + 1
            )
        ).isFalse()
    }

    @Test
    fun `a manual check after the floor is allowed again`() {
        assertThat(
            UpdateCheckPolicy.isManualCheckAllowed(
                now,
                lastAttemptAtMillis = now - UpdateCheckPolicy.MANUAL_CHECK_FLOOR_MS
            )
        ).isTrue()
    }

    @Test
    fun `a clock that moved backwards does not lock manual checks out`() {
        assertThat(UpdateCheckPolicy.isManualCheckAllowed(now, lastAttemptAtMillis = now + hour))
            .isTrue()
    }
}
