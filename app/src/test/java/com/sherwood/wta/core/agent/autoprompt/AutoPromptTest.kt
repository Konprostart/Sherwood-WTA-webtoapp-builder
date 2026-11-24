package com.sherwood.wta.core.agent.autoprompt

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

class AutoPromptTest {

    private fun prompt(
        name: String = "Research",
        description: String = "",
        text: String = "Do the thing",
        category: AutoPromptCategory = AutoPromptCategory.CUSTOM,
        enabled: Boolean = true
    ) = AutoPrompt(
        name = name,
        description = description,
        text = text,
        category = category,
        enabled = enabled
    )

    @Test
    fun `a prompt requires a name and a body`() {
        assertThat(AutoPrompt(name = "", text = "body").validationError())
            .isEqualTo(AutoPrompt.ValidationError.NAME_REQUIRED)
        assertThat(AutoPrompt(name = "   ", text = "body").validationError())
            .isEqualTo(AutoPrompt.ValidationError.NAME_REQUIRED)
        assertThat(AutoPrompt(name = "name", text = "   ").validationError())
            .isEqualTo(AutoPrompt.ValidationError.TEXT_REQUIRED)
        assertThat(AutoPrompt(name = "name", text = "body").validationError()).isNull()
    }

    @Test
    fun `normalization trims every free-text field and keeps the identity`() {
        val raw = AutoPrompt(
            id = " keep-me ",
            name = "  Name  ",
            description = "  desc  ",
            text = "  body  "
        )
        val normalized = raw.normalized()
        assertThat(normalized.id).isEqualTo("keep-me")
        assertThat(normalized.name).isEqualTo("Name")
        assertThat(normalized.description).isEqualTo("desc")
        assertThat(normalized.text).isEqualTo("body")
    }

    @Test
    fun `search matches the name, the description, the body and the category`() {
        val research = prompt(
            name = "Investigate repo",
            description = "Licences and stack",
            text = "Summarise the README",
            category = AutoPromptCategory.RESEARCH
        )
        val coding = prompt(
            name = "Ship feature",
            description = "Implementation",
            text = "Write code and tests",
            category = AutoPromptCategory.CODING
        )
        val all = listOf(research, coding)

        assertThat(AutoPromptSearch.filter(all, "investigate")).containsExactly(research)
        assertThat(AutoPromptSearch.filter(all, "LICENCES")).containsExactly(research)
        assertThat(AutoPromptSearch.filter(all, "readme")).containsExactly(research)
        assertThat(AutoPromptSearch.filter(all, "coding")).containsExactly(coding)
        assertThat(AutoPromptSearch.filter(all, "nothing-here")).isEmpty()
    }

    @Test
    fun `a blank query keeps every prompt`() {
        val all = listOf(prompt(name = "A"), prompt(name = "B"))
        assertThat(AutoPromptSearch.filter(all, "")).containsExactlyElementsIn(all).inOrder()
        assertThat(AutoPromptSearch.filter(all, "   ")).containsExactlyElementsIn(all).inOrder()
    }

    @Test
    fun `unknown stored category values fall back to custom`() {
        assertThat(AutoPromptCategory.fromStored(null)).isEqualTo(AutoPromptCategory.CUSTOM)
        assertThat(AutoPromptCategory.fromStored("NOT_A_CATEGORY"))
            .isEqualTo(AutoPromptCategory.CUSTOM)
        assertThat(AutoPromptCategory.fromStored("SECURITY")).isEqualTo(AutoPromptCategory.SECURITY)
    }

    @Test
    fun `sanitize repairs the fields Gson can leave null`() {
        // Gson bypasses the constructor; a hand-edited document can deliver nulls for
        // fields the Kotlin type declares non-null.
        val raw = """
            {"id":null,"name":null,"description":null,"text":"body",
             "enabled":true,"category":null,"agentProfileId":null,"modelId":null}
        """.trimIndent()
        val parsed = Gson().fromJson(raw, AutoPrompt::class.java)

        val safe = parsed.sanitized()
        assertThat(safe.id).isNotEmpty()
        assertThat(safe.name).isEmpty()
        assertThat(safe.description).isEmpty()
        assertThat(safe.text).isEqualTo("body")
        assertThat(safe.enabled).isTrue()
        assertThat(safe.category).isEqualTo(AutoPromptCategory.CUSTOM)
        assertThat(safe.agentProfileId).isNull()
        assertThat(safe.modelId).isNull()
    }

    @Test
    fun `sanitize keeps a blank profile or model id out of the prompt`() {
        val parsed = Gson().fromJson(
            """{"id":"p","name":"N","text":"T","enabled":true,
                "category":"CODING","agentProfileId":"  ","modelId":"  "}""",
            AutoPrompt::class.java
        )
        val safe = parsed.sanitized()
        assertThat(safe.agentProfileId).isNull()
        assertThat(safe.modelId).isNull()
        assertThat(safe.category).isEqualTo(AutoPromptCategory.CODING)
    }
}
