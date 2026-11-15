package com.sherwood.wta.core.telegram

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

        private val SUPPORTED = setOf(
            "start", "help", "status", "repos", "runs", "build",
            "vault", "apk", "pair", "version"
        )

        fun supports(name: String): Boolean = name.lowercase() in SUPPORTED

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

/** What the harness should do with one inbound message. */
sealed class TelegramReply {
    data class Text(val text: String) : TelegramReply()

    /** A vault artifact small enough to upload as a chat document. */
    data class Document(val file: File, val caption: String) : TelegramReply()

    /** Nothing to send: the message was not addressed to the harness. */
    data object Ignore : TelegramReply()
}
