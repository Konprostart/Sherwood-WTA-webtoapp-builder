package com.sherwood.wta.core.agent.prompt.sections

/**
 * A user-authored addendum from the active agent profile.
 *
 * Kept separate from the built-in sections so a profile can sharpen behaviour without
 * editing (or being able to weaken) the base instructions. Placement is after the
 * behaviour rules and before the tool catalogue, which reads as "these are my extra
 * rules", not "these replace the rules".
 */
internal object ProfileSection {
    fun build(addendum: String): String {
        val cleaned = addendum.trim()
        if (cleaned.isEmpty()) return ""
        return "# Profile instructions\n\n$cleaned"
    }
}
