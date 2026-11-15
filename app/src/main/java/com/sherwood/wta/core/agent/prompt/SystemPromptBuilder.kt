package com.sherwood.wta.core.agent.prompt

import com.sherwood.wta.core.agent.prompt.sections.BehaviorSection
import com.sherwood.wta.core.agent.prompt.sections.EnvironmentSection
import com.sherwood.wta.core.agent.prompt.sections.IdentitySection
import com.sherwood.wta.core.agent.prompt.sections.PlanModeSection
import com.sherwood.wta.core.agent.prompt.sections.ProfileSection
import com.sherwood.wta.core.agent.prompt.sections.ProjectFilesSection
import com.sherwood.wta.core.agent.prompt.sections.ToolUsageSection
import com.sherwood.wta.core.agent.tool.Tool
import com.sherwood.wta.core.i18n.AppLanguage

object SystemPromptBuilder {

    data class Input(
        val language: AppLanguage,
        val modelName: String,
        val sessionDir: String,
        val tools: List<Tool>,
        val projectFiles: List<ProjectFilesSection.FileSummary>,

        val planMode: PlanMode? = null,

        val selectedContext: String = "",

        /** Addendum from the active agent profile; blank for the default profile. */
        val profileAddendum: String = ""
    )

    data class PlanMode(
        val planFilePath: String,
        val planExists: Boolean
    )

    fun build(input: Input): String {
        val lang = input.language.toPromptLang()
        val sections = buildList {
            add(IdentitySection.build(lang))
            add(BehaviorSection.build(lang))
            add(ProfileSection.build(input.profileAddendum))
            add(ToolUsageSection.build(lang, input.tools))
            add(EnvironmentSection.build(lang, input.modelName, input.sessionDir))
            if (input.selectedContext.isNotBlank()) add(input.selectedContext)
            add(ProjectFilesSection.build(lang, input.projectFiles))
            input.planMode?.let { add(PlanModeSection.build(lang, it.planFilePath, it.planExists)) }
        }
        return sections
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trimEnd()
    }
}
