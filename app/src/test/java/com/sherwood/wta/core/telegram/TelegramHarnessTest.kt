package com.sherwood.wta.core.telegram

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.sherwood.wta.core.studio.git.GitPipelineRun
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the pieces of the Telegram harness that decide *what* is allowed:
 * command parsing, the pairing handshake, and the token format gate.
 *
 * The network and the foreground service are deliberately not exercised here — those
 * are integration concerns — but everything that can silently grant access to a stranger
 * is unit-tested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TelegramHarnessTest {

    private lateinit var settings: TelegramSettings

    @Before
    fun setUp() {
        settings = TelegramSettings.getInstance(ApplicationProvider.getApplicationContext())
        settings.pairedChats = emptySet()
        settings.allowRemoteBuilds = true
        settings.clearPairingCode()
    }

    // ------------------------------------------------------------- parsing

    @Test
    fun `parses a bare command`() {
        val command = TelegramCommand.parse("/status")
        assertThat(command).isNotNull()
        assertThat(command!!.name).isEqualTo("status")
        assertThat(command.args).isEmpty()
    }

    @Test
    fun `strips the bot mention suffix`() {
        val command = TelegramCommand.parse("/build@SherwoodBot main ci.yml")
        assertThat(command!!.name).isEqualTo("build")
        assertThat(command.args).containsExactly("main", "ci.yml").inOrder()
    }

    @Test
    fun `plain text is not a command`() {
        assertThat(TelegramCommand.parse("hello there")).isNull()
        assertThat(TelegramCommand.parse("")).isNull()
        assertThat(TelegramCommand.parse(null)).isNull()
    }

    @Test
    fun `arguments are bounded in count and length`() {
        val long = "x".repeat(500)
        val command = TelegramCommand.parse("/build a b c d e")!!
        assertThat(command.args).hasSize(TelegramCommand.MAX_ARGS)
        // The fourth and fifth arguments are dropped, never silently merged.
        assertThat(command.args).containsExactly("a", "b", "c").inOrder()

        val truncated = TelegramCommand.parse("/build $long")!!
        assertThat(truncated.args.single().length).isEqualTo(TelegramCommand.MAX_ARG_LENGTH)
    }

    // ------------------------------------------------------------- tokens

    @Test
    fun `accepts a real bot token shape`() {
        assertThat(TelegramTokenFormat.isValid("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw")).isTrue()
    }

    @Test
    fun `rejects a token without the numeric bot id`() {
        assertThat(TelegramTokenFormat.isValid("notabot:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw")).isFalse()
        assertThat(TelegramTokenFormat.isValid("AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw")).isFalse()
        assertThat(TelegramTokenFormat.isValid("")).isFalse()
    }

    @Test
    fun `bot id is readable without exposing the secret`() {
        assertThat(TelegramTokenFormat.botId("123456789:secret-part")).isEqualTo("123456789")
        assertThat(TelegramTokenFormat.botId("nope")).isNull()
    }

    // ------------------------------------------------------------- pairing

    @Test
    fun `a pairing code pairs exactly one chat and is single use`() {
        val code = settings.newPairingCode()

        val outcome = settings.redeemPairingCode(code.value, chatId = 42L)

        assertThat(outcome).isEqualTo(TelegramSettings.PairingOutcome.Paired)
        assertThat(settings.pairedChats).containsExactly(42L)
        // The code is consumed; a second chat cannot reuse it.
        assertThat(settings.redeemPairingCode(code.value, chatId = 43L))
            .isEqualTo(TelegramSettings.PairingOutcome.NoActiveCode)
        assertThat(settings.pairedChats).containsExactly(42L)
    }

    @Test
    fun `a wrong code does not pair`() {
        val code = settings.newPairingCode()
        val wrong = if (code.value == "000000") "000001" else "000000"

        assertThat(settings.redeemPairingCode(wrong, chatId = 7L))
            .isEqualTo(TelegramSettings.PairingOutcome.Mismatch)
        assertThat(settings.pairedChats).isEmpty()
    }

    @Test
    fun `an expired code cannot pair`() {
        val code = settings.newPairingCode(
            now = System.currentTimeMillis() - TelegramSettings.PAIRING_WINDOW_MS - 1
        )

        assertThat(settings.redeemPairingCode(code.value, chatId = 7L))
            .isEqualTo(TelegramSettings.PairingOutcome.Expired)
        assertThat(settings.pairedChats).isEmpty()
    }

    @Test
    fun `unpairing removes only the requested chat`() {
        settings.pairedChats = setOf(1L, 2L)
        val remaining = settings.unpair(1L)
        assertThat(remaining).containsExactly(2L)
        assertThat(settings.pairedChats).containsExactly(2L)
    }

    // ------------------------------------------ run terminality across hosts

    @Test
    fun `gitea style terminal statuses end the build poll`() {
        // Gitea/Forgejo never report "completed"; judging terminality by that word alone
        // made a finished build poll until the deadline.
        val giteaSuccess = run(status = "success", conclusion = null)
        assertThat(giteaSuccess.isTerminal).isTrue()
        assertThat(giteaSuccess.isSuccessful).isTrue()

        val giteaFailure = run(status = "failure", conclusion = null)
        assertThat(giteaFailure.isTerminal).isTrue()
        assertThat(giteaFailure.isSuccessful).isFalse()

        val giteaRunning = run(status = "running", conclusion = null)
        assertThat(giteaRunning.isTerminal).isFalse()
    }

    @Test
    fun `github style runs still work`() {
        val completed = run(status = "completed", conclusion = "success")
        assertThat(completed.isTerminal).isTrue()
        assertThat(completed.isSuccessful).isTrue()

        val queued = run(status = "queued", conclusion = null)
        assertThat(queued.isTerminal).isFalse()
    }

    private fun run(status: String, conclusion: String?) = GitPipelineRun(
        id = 1,
        name = "CI",
        workflowId = "",
        workflowPath = "ci.yml",
        status = status,
        conclusion = conclusion,
        branch = "main",
        headSha = null,
        event = null,
        runNumber = 1,
        displayTitle = null,
        createdAt = null,
        updatedAt = null,
        htmlUrl = null
    )
}
