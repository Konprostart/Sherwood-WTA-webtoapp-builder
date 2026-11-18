package com.sherwood.wta.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.data.converter.Converters
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The local half of a restore is a transaction: snapshot, journal, publish, and — on
 * any failure — put the previous files back.
 *
 * These tests exist because the previous implementation overwrote live settings,
 * DataStore and keystores *before* the database import began. A failure later in the
 * import left them replaced with no copy to return to; here that has to be impossible.
 */
@RunWith(RobolectricTestRunner::class)
class BackupLocalTransactionTest {

    private lateinit var context: Context
    private lateinit var workDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.filesDir, "tx_probe").apply { mkdirs() }
        LocalFileTransaction.defaultRollbackDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        LocalFileTransaction.defaultRollbackDir(context).deleteRecursively()
    }

    private fun staged(target: File, content: String): DataBackupManager.PendingLocalEntry {
        val temp = File.createTempFile("wta_backup_", ".part", context.cacheDir)
        temp.writeText(content)
        val bytes = content.toByteArray()
        return DataBackupManager.PendingLocalEntry(
            zipPath = "local/files/${target.name}",
            targetFile = target,
            tempFile = temp,
            size = bytes.size.toLong(),
            sha256 = BackupIntegrity.hex(bytes)
        )
    }

    @Test
    fun `commit publishes staged bytes and reports what it committed`() {
        val target = File(workDir, "settings.xml")
        val transaction = LocalFileTransaction(context)

        transaction.commit(listOf(staged(target, "restored")))

        assertThat(target.readText()).isEqualTo("restored")
        assertThat(transaction.committedEntries).isEqualTo(1)
        assertThat(transaction.anyCommitted).isTrue()
        assertThat(transaction.hasWork).isTrue()
    }

    @Test
    fun `an empty commit does nothing`() {
        val transaction = LocalFileTransaction(context)
        transaction.commit(emptyList())

        assertThat(transaction.anyCommitted).isFalse()
        assertThat(transaction.hasWork).isFalse()
        assertThat(LocalFileTransaction.defaultRollbackDir(context).exists()).isFalse()
    }

    @Test
    fun `rollback restores the original bytes of an overwritten file`() {
        val target = File(workDir, "theme.preferences_pb").apply { writeText("original") }
        val transaction = LocalFileTransaction(context)
        transaction.commit(listOf(staged(target, "restored")))
        assertThat(target.readText()).isEqualTo("restored")

        val failures = transaction.rollback()

        assertThat(failures).isEqualTo(0)
        assertThat(target.readText()).isEqualTo("original")
    }

    @Test
    fun `rollback deletes files that did not exist before the commit`() {
        val target = File(workDir, "new_file.txt")
        val transaction = LocalFileTransaction(context)
        transaction.commit(listOf(staged(target, "created")))
        assertThat(target.exists()).isTrue()

        transaction.rollback()

        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `rollback removes directories the commit created`() {
        val target = File(workDir, "fresh/nested/file.xml")
        val transaction = LocalFileTransaction(context)
        transaction.commit(listOf(staged(target, "data")))
        assertThat(target.exists()).isTrue()

        transaction.rollback()

        // "fresh" was created by the commit, so it must not survive as an empty shell.
        assertThat(File(workDir, "fresh").exists()).isFalse()
    }

    @Test
    fun `a failed publish rolls back the entries already written`() {
        val first = File(workDir, "first.xml").apply { writeText("first-original") }
        // Parent is a regular file: mkdirs cannot create a directory there, so the
        // second publish fails after the first one has already landed.
        val blocker = File(workDir, "blocker").apply { writeText("x") }
        val second = File(blocker, "second.xml")

        val transaction = LocalFileTransaction(context)
        val failure = runCatching {
            transaction.commit(listOf(staged(first, "first-restored"), staged(second, "second")))
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        // The first entry landed before the failure...
        assertThat(first.readText()).isEqualTo("first-restored")

        val failures = transaction.rollback()

        assertThat(failures).isEqualTo(0)
        // ...and is put back exactly as it was.
        assertThat(first.readText()).isEqualTo("first-original")
        assertThat(second.exists()).isFalse()
    }

    @Test
    fun `an interrupted commit is rolled back by the next transaction`() {
        val target = File(workDir, "activation.preferences_pb").apply { writeText("before") }

        // Simulate a process death after publishing but before the caller cleaned up.
        val interrupted = LocalFileTransaction(context)
        interrupted.commit(listOf(staged(target, "half-applied")))
        assertThat(target.readText()).isEqualTo("half-applied")
        assertThat(LocalFileTransaction.defaultRollbackDir(context).exists()).isTrue()

        val next = LocalFileTransaction(context)
        val recovered = next.recoverInterrupted()

        assertThat(recovered).isTrue()
        assertThat(target.readText()).isEqualTo("before")
        assertThat(LocalFileTransaction.defaultRollbackDir(context).exists()).isFalse()
    }

    @Test
    fun `recovery refuses journal paths outside the app sandbox`() {
        val outside = File(System.getProperty("java.io.tmpdir")!!, "wta_outside_${System.nanoTime()}.txt")
        outside.writeText("must not change")
        try {
            val dir = LocalFileTransaction.defaultRollbackDir(context).apply { mkdirs() }
            File(dir, "restore_journal.json").writeText(
                """{"version":1,"entries":[{"target":"${outside.absolutePath}","backup":null,"existed":true}],"createdDirs":[]}"""
            )

            val recovered = LocalFileTransaction(context).recoverInterrupted()

            assertThat(recovered).isFalse()
            assertThat(outside.readText()).isEqualTo("must not change")
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a journal without a readable plan is discarded instead of acted on`() {
        val dir = LocalFileTransaction.defaultRollbackDir(context).apply { mkdirs() }
        File(dir, "restore_journal.json").writeText("{ not json")

        val recovered = LocalFileTransaction(context).recoverInterrupted()

        assertThat(recovered).isFalse()
        assertThat(dir.exists()).isFalse()
    }

    @Test
    fun `commit leaves no temp file or rollback directory behind after cleanup`() {
        val target = File(workDir, "clean.xml")
        val transaction = LocalFileTransaction(context)
        transaction.commit(listOf(staged(target, "value")))
        transaction.cleanup()

        val leftovers = workDir.listFiles()?.filter { it.name.startsWith(".wta-tmp-") } ?: emptyList()
        assertThat(leftovers).isEmpty()
        assertThat(LocalFileTransaction.defaultRollbackDir(context).exists()).isFalse()
        assertThat(target.readText()).isEqualTo("value")
    }

    @Test
    fun `staging records the digest of the bytes it staged`() {
        val manager = DataBackupManager(context)
        val entry = manager.stageLocalBackupEntry(
            "local/files/aicoding/project.txt",
            zipStream("hello world")
        )

        assertThat(entry).isNotNull()
        assertThat(entry!!.size).isEqualTo("hello world".toByteArray().size.toLong())
        assertThat(entry.sha256).isEqualTo(BackupIntegrity.hex("hello world".toByteArray()))
        assertThat(entry.fingerprint.path).isEqualTo("local/files/aicoding/project.txt")
        entry.tempFile.delete()
    }

    private fun zipStream(content: String): java.util.zip.ZipInputStream {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("payload"))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(buffer.toByteArray()))
            .also { check(it.nextEntry != null) }
    }

    @Test
    fun `the journal is written before the first live byte`() {
        // A commit that fails on its very first publish must still leave a plan on
        // disk: that is what makes a process death between publish and cleanup
        // recoverable rather than a silent half-restore.
        val blocker = File(workDir, "journal_blocker").apply { writeText("x") }
        val target = File(blocker, "child.xml")
        val transaction = LocalFileTransaction(context)

        runCatching { transaction.commit(listOf(staged(target, "value"))) }

        val journal = File(LocalFileTransaction.defaultRollbackDir(context), "restore_journal.json")
        assertThat(journal.isFile).isTrue()
        assertThat(journal.readText()).contains(target.absolutePath)
        // Keep the assertion honest: the stored plan must be parseable JSON.
        assertThat(Converters.gson.fromJson(journal.readText(), com.google.gson.JsonObject::class.java))
            .isNotNull()

        transaction.rollback()
    }
}
