package com.sherwood.wta.core.agent.session

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The session store split a legacy all-in-one blob into an index plus one message blob
 * per session. These tests pin the split itself — the part that runs once for existing
 * users — because getting it wrong loses every conversation at once.
 */
class SessionMigrationTest {

    private val gson = Gson()

    private fun legacyJson(sessionCount: Int): String = buildString {
        append("[")
        repeat(sessionCount) { i ->
            if (i > 0) append(",")
            append(
                """
                {
                  "id": "session-$i",
                  "title": "Session $i",
                  "messages": [
                    {"id": "m-$i-a", "role": "USER", "content": "hello $i"},
                    {"id": "m-$i-b", "role": "ASSISTANT", "content": "hi $i"}
                  ]
                }
                """.trimIndent()
            )
        }
        append("]")
    }

    @Test
    fun `split moves messages out of the index`() {
        val split = SessionMigration.split(legacyJson(3), gson)!!

        val index = JsonParser.parseString(split.index).asJsonArray
        assertThat(index).hasSize(3)
        index.forEach { element ->
            val session = element.asJsonObject
            // The index must stay metadata-only, otherwise the write amplification
            // this migration removes would come straight back.
            assertThat(session.getAsJsonArray("messages")).isEmpty()
            assertThat(session.get("title").asString).isNotEmpty()
        }

        assertThat(split.messages).hasSize(3)
        assertThat(split.messages.keys).containsExactly("session-0", "session-1", "session-2")
        val first = JsonParser.parseString(split.messages["session-0"]).asJsonArray
        assertThat(first).hasSize(2)
    }

    @Test
    fun `split preserves every message and its content`() {
        val split = SessionMigration.split(legacyJson(2), gson)!!
        val contents = split.messages.values
            .flatMap { JsonParser.parseString(it).asJsonArray }
            .map { it.asJsonObject.get("content").asString }
        assertThat(contents).containsExactly("hello 0", "hi 0", "hello 1", "hi 1")
    }

    @Test
    fun `split returns null for unparseable input so the blob is never destroyed`() {
        assertThat(SessionMigration.split("{not json", gson)).isNull()
    }

    @Test
    fun `decoded index restores fields that legacy JSON omitted`() {
        val legacy = """[{"id":"s1","title":"t","messages":[]}]"""
        val decoded = SessionMigration.decodeIndex(legacy, gson)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].planSlugs).isEmpty()
        assertThat(decoded[0].config.customRules).isEmpty()
    }

    @Test
    fun `message blob decode normalises missing lists`() {
        val raw = """[{"id":"m1","role":"USER","content":"x"}]"""
        val decoded = SessionMigration.decodeMessages(raw, gson)
        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].userAttachmentsSafe).isEmpty()
        assertThat(decoded[0].thinkingSegmentsSafe).isEmpty()
        assertThat(decoded[0].toolCalls).isEmpty()
    }
}
