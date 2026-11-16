package com.sherwood.wta.core.sidekick

import android.content.Context

/**
 * Preferences for the floating in-app assistant ("Sidekick").
 *
 * The Sidekick is an advisory overlay: it answers questions about what the main agent is
 * doing, explains errors and rewrites prompts. It never executes tools and never touches
 * project files, so its state is a handful of plain preferences rather than the agent's
 * session/settings documents.
 *
 * Every default here preserves "off" for the overlay: a floating button that appears
 * without being asked for would cover the user's own UI on the next start.
 */
class SidekickSettings private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Live change notification.
     *
     * The overlay is hosted above the navigation graph while the switches live on the
     * settings screen — two different destinations in the same activity, so no Activity
     * lifecycle event fires when the user toggles the feature and navigates back. Without
     * this, enabling the assistant would only take effect after backgrounding the app.
     */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // SharedPreferences only keeps a weak reference, so this field must stay alive.
    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            changeListeners.forEach { listener -> listener() }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /** Returns a detach function; callers are expected to keep it and call it on clear. */
    fun observe(onChange: () -> Unit): () -> Unit {
        changeListeners.add(onChange)
        return { changeListeners.remove(onChange) }
    }

    /** Whether the floating entry point is shown at all. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * The model the Sidekick answers with. `null` follows the model the main agent uses,
     * which is the behaviour most users want and the only one that stays correct when the
     * agent's model is switched.
     */
    var modelId: String?
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) editor.remove(KEY_MODEL) else editor.putString(KEY_MODEL, value)
            editor.apply()
        }

    /**
     * Whether the tail of the current agent session is handed to the Sidekick as
     * read-only context. On by default: "explain what the agent just did" cannot be
     * answered without it.
     */
    var includeAgentContext: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_CONTEXT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_INCLUDE_CONTEXT, value).apply()
        }

    /**
     * Vertical anchor of the floating button, `0f` = top of its travel, `1f` = bottom.
     *
     * The button floats above every screen, so it will overlap something no matter where
     * it starts; being draggable and remembering where it was left is what keeps it from
     * permanently covering a control the user needs.
     */
    var fabAnchor: Float
        get() = prefs.getFloat(KEY_FAB_ANCHOR, DEFAULT_FAB_ANCHOR).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_FAB_ANCHOR, value.coerceIn(0f, 1f)).apply()
        }

    /** A stored model id that no longer resolves must not silently pin a dead model. */
    fun resetModel() {
        prefs.edit().remove(KEY_MODEL).apply()
    }

    companion object {
        private const val PREFS = "sherwood_sidekick"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODEL = "model_id"
        private const val KEY_INCLUDE_CONTEXT = "include_agent_context"
        private const val KEY_FAB_ANCHOR = "fab_anchor"

        /** Slightly above the bottom edge: clear of a navigation bar, still thumb-reachable. */
        const val DEFAULT_FAB_ANCHOR = 0.92f

        @Volatile
        private var INSTANCE: SidekickSettings? = null

        fun getInstance(context: Context): SidekickSettings =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SidekickSettings(context.applicationContext).also { INSTANCE = it }
            }
    }
}
