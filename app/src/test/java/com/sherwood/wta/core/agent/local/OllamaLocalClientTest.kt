package com.sherwood.wta.core.agent.local

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.Test

/**
 * The local-model driver is the offline path, so its two failure modes are tested
 * directly: an address that should never be contacted, and a server response that must
 * not be read as "the model answered nothing".
 */
class OllamaLocalClientTest {

    @Test
    fun `parses and normalises loopback and LAN addresses`() {
        assertThat(OllamaEndpoint.parse("http://127.0.0.1:11434")?.baseUrl)
            .isEqualTo("http://127.0.0.1:11434")
        assertThat(OllamaEndpoint.parse("  http://192.168.1.20:11434/  ")?.baseUrl)
            .isEqualTo("http://192.168.1.20:11434")
        assertThat(OllamaEndpoint.parse("https://ollama.example.test")?.baseUrl)
            .isEqualTo("https://ollama.example.test")
        // A reverse-proxied server under a path keeps the path.
        assertThat(OllamaEndpoint.parse("http://host:8080/ollama")?.baseUrl)
            .isEqualTo("http://host:8080/ollama")
    }

    @Test
    fun `rejects addresses that must never be contacted`() {
        assertThat(OllamaEndpoint.parse("")).isNull()
        assertThat(OllamaEndpoint.parse("127.0.0.1:11434")).isNull()
        assertThat(OllamaEndpoint.parse("ftp://127.0.0.1:11434")).isNull()
        // Credentials would leak into logs and error text.
        assertThat(OllamaEndpoint.parse("http://user:pass@127.0.0.1:11434")).isNull()
        assertThat(OllamaEndpoint.parse("http://127.0.0.1:11434/?token=abc")).isNull()
        assertThat(OllamaEndpoint.parse("http://127.0.0.1:11434/#frag")).isNull()
        assertThat(OllamaEndpoint.parse("http://127.0.0.1:11434/a/../b")).isNull()
    }

    @Test
    fun `localhost detection is exact`() {
        assertThat(OllamaEndpoint.isLocalHost("http://127.0.0.1:11434")).isTrue()
        assertThat(OllamaEndpoint.isLocalHost("http://localhost:11434")).isTrue()
        assertThat(OllamaEndpoint.isLocalHost("http://192.168.1.20:11434")).isFalse()
        assertThat(OllamaEndpoint.isLocalHost("nonsense")).isFalse()
    }

    @Test
    fun `parses a tags response and keeps vision families marked`() {
        val json = JsonParser.parseString(
            """
            {"models":[
              {"name":"llama3.2:3b","size":2019393189,
               "details":{"family":"llama","parameter_size":"3.2B","quantization_level":"Q4_K_M"}},
              {"name":"llava:7b","size":4700000000,
               "details":{"family":"llama","parameter_size":"7B","quantization_level":"Q4_0"}},
              {"name":"qwen2.5:7b","size":4700000000,
               "details":{"parameter_size":"7B","quantization_level":"Q4_K_M"}}
            ]}
            """.trimIndent()
        ).asJsonObject

        val models = OllamaResponseParser.parseModels(json)
        assertThat(models).hasSize(3)
        val llava = models.first { it.name == "llava:7b" }
        assertThat(llava.isMultimodal).isTrue()
        assertThat(llava.parameterSize).isEqualTo("7B")
        assertThat(llava.quantization).isEqualTo("Q4_0")
        assertThat(models.first { it.name == "llama3.2:3b" }.isMultimodal).isFalse()
    }

    @Test
    fun `a malformed entry is skipped instead of failing the list`() {
        val json = JsonParser.parseString(
            """{"models":[{"size":1},{"name":"good:latest","size":2}]}"""
        ).asJsonObject
        val models = OllamaResponseParser.parseModels(json)
        assertThat(models.map { it.name }).containsExactly("good:latest")
    }

    @Test
    fun `missing or unreadable model list is empty, never invented`() {
        assertThat(OllamaResponseParser.parseModels(JsonParser.parseString("{}").asJsonObject))
            .isEmpty()
        assertThat(OllamaResponseParser.parseModels(JsonParser.parseString("""{"models":"x"}""").asJsonObject))
            .isEmpty()
    }

    @Test
    fun `profile ids are stable and filesystem-safe`() {
        assertThat(LocalAgentModels.profileIdFor("qwen2.5:7b")).isEqualTo("local-qwen2.5-7b")
        assertThat(LocalAgentModels.profileIdFor("Llama 3.2")).isEqualTo("local-llama-3.2")
        assertThat(LocalAgentModels.profileIdFor("模型")).isNotEqualTo("local-")
        // The same model always lands on the same profile, so re-binding updates one.
        assertThat(LocalAgentModels.profileIdFor("qwen2.5:7b"))
            .isEqualTo(LocalAgentModels.profileIdFor("qwen2.5:7b"))
    }
}
