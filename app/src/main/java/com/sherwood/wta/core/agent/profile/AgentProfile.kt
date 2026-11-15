package com.sherwood.wta.core.agent.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sherwood.wta.core.logging.AppLogger

/**
 * A named way of running the agent.
 *
 * Until now the agent had exactly one personality: the model picked in AI settings, the
 * built-in system prompt, every tool, and the temperature stored on the session. A
 * profile turns those four implicit decisions into data, so a user can keep e.g. a
 * "review only" profile that never writes files, or a "local" profile pinned to a local
 * model, without forking the engine.
 *
 * Every field is optional and falls back to the current behaviour, so the default profile
 * changes nothing.
 */
data class AgentProfile(
    val id: String,
    val name: String,
    /** Preferred text model id; null keeps the model chosen in AI settings. */
    val modelId: String? = null,
    /** Overrides the session temperature when set. */
    val temperature: Float? = null,
    /** Overrides the session step budget when set. */
    val maxSteps: Int? = null,
    /** Appended to the built-in system prompt as its own section. */
    val promptAddendum: String = "",
    /**
     * Tool names this profile may use; null means every built-in tool. An empty set is a
     * valid, deliberately tool-free profile (a reviewer that may only answer).
     */
    val toolAllowlist: Set<String>? = null,
    /**
     * Which runner executes the turn. `"llm"` is the built-in agent loop; the id exists
     * so an alternative runner (a local instruction executor, an Open-Code-style driver)
     * can be selected without changing the engine or the UI.
     */
    val runnerId: String = RUNNER_LLM
) {
    companion object {
        const val RUNNER_LLM = "llm"
        const val DEFAULT_ID = "default"

        /** The pre-profile behaviour, expressed as a profile. */
        val DEFAULT = AgentProfile(id = DEFAULT_ID, name = "Default")
    }
}

/**
 * Persistence for profiles and the active selection.
 *
 * Stored as one small JSON document rather than per-field preferences: a profile is
 * edited as a unit, and a half-written profile would be worse than none. A corrupt
 * document falls back to [AgentProfile.DEFAULT] and is reported, never silently applied.
 */
class AgentProfileStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    var activeId: String
        get() = prefs.getString(KEY_ACTIVE, AgentProfile.DEFAULT_ID) ?: AgentProfile.DEFAULT_ID
        set(value) {
            prefs.edit().putString(KEY_ACTIVE, value).apply()
        }

    /** The default profile plus every saved one, the default always first. */
    fun profiles(): List<AgentProfile> {
        val saved = readSaved()
        return buildList {
            add(AgentProfile.DEFAULT)
            addAll(saved.filter { it.id != AgentProfile.DEFAULT_ID })
        }
    }

    /** The active profile, or the default when the saved one disappeared. */
    fun active(): AgentProfile {
        val id = activeId
        return profiles().firstOrNull { it.id == id } ?: AgentProfile.DEFAULT
    }

    fun save(profile: AgentProfile): Result<Unit> {
        val cleaned = profile.copy(
            id = profile.id.trim(),
            name = profile.name.trim().ifBlank { profile.id.trim() }
        )
        if (cleaned.id.isEmpty()) {
            return Result.failure(IllegalArgumentException("Profile id is required"))
        }
        val next = profiles().filterNot { it.id == cleaned.id } + cleaned
        return write(next.filter { it.id != AgentProfile.DEFAULT_ID })
    }

    fun delete(id: String): Result<Unit> {
        if (id == AgentProfile.DEFAULT_ID) {
            return Result.failure(IllegalArgumentException("The default profile cannot be deleted"))
        }
        val next = profiles().filterNot { it.id == id }
        val result = write(next.filter { it.id != AgentProfile.DEFAULT_ID })
        if (result.isSuccess && activeId == id) activeId = AgentProfile.DEFAULT_ID
        return result
    }

    private fun readSaved(): List<AgentProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<AgentProfile>>() {}.type
            gson.fromJson<List<AgentProfile>>(raw, type).orEmpty()
        }.getOrElse { error ->
            AppLogger.w(TAG, "Stored agent profiles could not be read: ${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun write(profiles: List<AgentProfile>): Result<Unit> {
        val encoded = runCatching { gson.toJson(profiles) }.getOrElse {
            return Result.failure(it)
        }
        return if (prefs.edit().putString(KEY_PROFILES, encoded).commit()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Could not save agent profiles"))
        }
    }

    companion object {
        private const val TAG = "AgentProfileStore"
        private const val PREFS = "sherwood_agent_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"

        @Volatile
        private var INSTANCE: AgentProfileStore? = null

        fun getInstance(context: Context): AgentProfileStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentProfileStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
