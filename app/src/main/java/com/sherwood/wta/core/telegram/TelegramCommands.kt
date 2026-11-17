package com.sherwood.wta.core.telegram

import com.sherwood.wta.core.i18n.Strings
import java.io.File

/**
 * A parsed slash command.
 *
 * Parsing is separated from execution so the awkward cases — `@BotName` suffixes,
 * padded whitespace, over-long arguments, unknown names — are unit-testable without a
 * network, a token or an Android service.
 */
data class TelegramCommand(val name: String, val args: List<String>) {

    /** First argument, or null when the command was sent bare. */
    val firstArg: String? get() = args.firstOrNull()

    fun arg(index: Int): String? = args.getOrNull(index)

    companion object {
        /** Arguments beyond this are dropped: no command takes more than three. */
        const val MAX_ARGS = 3

        /** A single argument longer than this is truncated, not rejected. */
        const val MAX_ARG_LENGTH = 200

        /**
         * Parses `/name arg1 arg2` (optionally `/name@BotName`).
         *
         * Returns null for anything that is not a command — plain chat text is ignored
         * rather than answered, so a chat used for notifications never turns into a
         * conversation.
         */
        fun parse(text: String?): TelegramCommand? {
            val trimmed = text?.trim().orEmpty()
            if (!trimmed.startsWith('/')) return null
            val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val rawName = parts.first().removePrefix("/")
            val name = rawName.substringBefore('@').lowercase()
            if (name.isEmpty()) return null
            val args = parts.drop(1)
                .take(MAX_ARGS)
                .map { it.take(MAX_ARG_LENGTH) }
                .filter { it.isNotEmpty() }
            return TelegramCommand(name, args)
        }
    }
}

/**
 * One inline-keyboard button.
 *
 * A button either answers a tap through `callback_data` ([data]) or opens a page ([url]).
 * [data] is capped at 64 bytes by the Bot API, so [isValid] rejects anything that would
 * make the whole message fail to send; the router drops an invalid button rather than
 * losing the reply.
 */
data class TelegramButton(
    val label: String,
    val data: String = "",
    val url: String? = null
) {
    val isValid: Boolean
        get() = label.isNotBlank() && when {
            !url.isNullOrBlank() -> url.startsWith("https://")
            else -> data.isNotBlank() &&
                data.toByteArray(Charsets.UTF_8).size <= MAX_CALLBACK_BYTES
        }

    companion object {
        /** Telegram's hard limit on `callback_data`. */
        const val MAX_CALLBACK_BYTES = 64

        fun callback(label: String, data: String) = TelegramButton(label = label, data = data)

        fun link(label: String, url: String) = TelegramButton(label = label, url = url)
    }
}

/** What the harness should do with one inbound message or button tap. */
sealed class TelegramReply {
    data class Text(
        val text: String,
        /** Rows of inline buttons; empty means a plain message. */
        val keyboard: List<List<TelegramButton>> = emptyList()
    ) : TelegramReply()

    /** A vault artifact small enough to upload as a chat document. */
    data class Document(
        val file: File,
        val caption: String,
        val keyboard: List<List<TelegramButton>> = emptyList()
    ) : TelegramReply()

    /** Nothing to send: the message was not addressed to the harness. */
    data object Ignore : TelegramReply()
}

/**
 * The callback-data vocabulary of the inline keyboard.
 *
 * Kept tiny and whitelisted: a tap payload is never interpolated into a path, a URL or a
 * shell string, it only selects one of the handlers below. `build:<id>` carries a
 * workflow id, which is validated against a conservative character class before use.
 */
object TelegramControls {

    const val STATUS = "st"
    const val RUNS = "rn"
    const val REPOS = "rp"
    const val VAULT = "vl"
    const val APK = "ap"
    const val BUILD = "bd"
    const val BUILD_PREFIX = "bd:"

    const val APK_PREFIX = "ap:"

    private val KNOWN = Regex("^(st|rn|rp|vl|ap|ap:[0-9]{1,19}|bd|bd:[A-Za-z0-9._/-]{1,48})$")
    private val WORKFLOW = Regex("^[A-Za-z0-9._/-]{1,48}$")

    fun isValid(data: String): Boolean = KNOWN.matches(data)

    fun isValidWorkflowId(id: String): Boolean = WORKFLOW.matches(id)

    /** Callback data for a build button, or null when the id cannot be represented. */
    fun buildData(workflowId: String?): String? = when {
        workflowId.isNullOrBlank() -> BUILD
        isValidWorkflowId(workflowId) -> BUILD_PREFIX + workflowId
        else -> null
    }

    /**
     * Callback data for downloading the APK of a specific run. A notification must not
     * download "whatever is newest": that could be a different commit.
     */
    fun apkData(runId: Long?): String =
        if (runId == null || runId <= 0L) APK else APK_PREFIX + runId
}

/**
 * Keyboard layouts shared by command replies and build notifications.
 *
 * They live here (not in the router) so the foreground service can attach the same
 * buttons to a push notification without duplicating the payload strings.
 */
object TelegramKeyboards {

    /**
     * Buttons for a finished CI run: download the newest vaulted APK, open the run page,
     * or return to status. A missing piece is omitted rather than shown broken.
     */
    fun buildResult(
        runUrl: String?,
        hasDownload: Boolean,
        runId: Long? = null
    ): List<List<TelegramButton>> {
        val actionable = buildList {
            if (hasDownload) {
                add(
                    TelegramButton.callback(
                        Strings.telegramButtonDownloadApk,
                        TelegramControls.apkData(runId)
                    )
                )
            }
            if (!runUrl.isNullOrBlank() && runUrl.startsWith("https://")) {
                add(TelegramButton.link(Strings.telegramButtonOpenRun, runUrl))
            }
        }
        return buildList {
            if (actionable.isNotEmpty()) add(actionable)
            add(listOf(TelegramButton.callback(Strings.telegramButtonStatus, TelegramControls.STATUS)))
        }
    }
}
