package com.sherwood.wta.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * API keys inside a backup are encrypted with the exporting install's AndroidKeyStore
 * key. Publishing that ciphertext over the local file would make the local AI settings
 * permanently unreadable, so a restore keeps the local config when one exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupAiConfigPreservationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun entry(zipPath: String, target: File): DataBackupManager.PendingLocalEntry {
        val staged = File.createTempFile("wta_commit_", ".part", context.cacheDir)
        staged.writeText("backup-keys")
        return DataBackupManager.PendingLocalEntry(
            zipPath = zipPath,
            targetFile = target,
            tempFile = staged,
            size = 0L,
            sha256 = ""
        )
    }

    @Test
    fun `an existing local AI config is not overwritten by the backup`() {
        val target = File(context.filesDir, "datastore/ai_config.preferences_pb")
        target.parentFile?.mkdirs()
        target.writeText("local-keys")
        try {
            assertThat(DataBackupManager(context).keepLocalAiConfig(entry(DataBackupManager.AI_CONFIG_DATASTORE_ENTRY, target)))
                .isTrue()
        } finally {
            target.delete()
        }
    }

    @Test
    fun `a missing local AI config still imports the backup file`() {
        val target = File(context.filesDir, "datastore/ai_config.preferences_pb")
        target.delete()
        assertThat(DataBackupManager(context).keepLocalAiConfig(entry(DataBackupManager.AI_CONFIG_DATASTORE_ENTRY, target)))
            .isFalse()
    }

    @Test
    fun `other datastore entries are always restored`() {
        val target = File(context.filesDir, "datastore/theme_settings.preferences_pb")
        target.parentFile?.mkdirs()
        target.writeText("local-theme")
        try {
            assertThat(DataBackupManager(context).keepLocalAiConfig(entry("local/datastore/theme_settings.preferences_pb", target)))
                .isFalse()
        } finally {
            target.delete()
        }
    }
}
