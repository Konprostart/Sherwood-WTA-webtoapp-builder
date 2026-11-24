package com.sherwood.wta.core.agent.autoprompt

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class AutoPromptHandoffTest {

    @Before
    fun setUp() {
        AutoPromptHandoff.clear()
    }

    private fun request(
        sessionId: String = "s1",
        createdAt: Long = System.currentTimeMillis()
    ) = AutoPromptHandoff.Request(
        sessionId = sessionId,
        promptText = "Do the thing",
        profileId = "profile",
        modelId = "model",
        createdAt = createdAt
    )

    @Test
    fun `a published request is consumed exactly once`() {
        AutoPromptHandoff.publish(request())

        assertThat(AutoPromptHandoff.consume("s1")?.promptText).isEqualTo("Do the thing")
        assertThat(AutoPromptHandoff.consume("s1")).isNull()
    }

    @Test
    fun `a request for another session is not consumed`() {
        AutoPromptHandoff.publish(request(sessionId = "s1"))

        assertThat(AutoPromptHandoff.consume("other")).isNull()
        // It is still waiting for the session it belongs to.
        assertThat(AutoPromptHandoff.consume("s1")).isNotNull()
    }

    @Test
    fun `peeking does not consume`() {
        AutoPromptHandoff.publish(request())

        assertThat(AutoPromptHandoff.peek("s1")).isNotNull()
        assertThat(AutoPromptHandoff.peek("s1")).isNotNull()
        assertThat(AutoPromptHandoff.consume("s1")).isNotNull()
    }

    @Test
    fun `an expired request is dropped instead of firing late`() {
        val now = 10_000_000L
        AutoPromptHandoff.publish(request(createdAt = now - AutoPromptHandoff.TTL_MS - 1))

        assertThat(AutoPromptHandoff.consume("s1", now = now)).isNull()
        assertThat(AutoPromptHandoff.peek("s1", now = now)).isNull()
    }

    @Test
    fun `a request just inside the window still fires`() {
        val now = 10_000_000L
        AutoPromptHandoff.publish(request(createdAt = now - AutoPromptHandoff.TTL_MS + 1))

        assertThat(AutoPromptHandoff.consume("s1", now = now)).isNotNull()
    }

    @Test
    fun `clear drops a pending request`() {
        AutoPromptHandoff.publish(request())
        AutoPromptHandoff.clear()
        assertThat(AutoPromptHandoff.consume("s1")).isNull()
    }
}
