package com.sherwood.wta.core.agent.tool

import com.sherwood.wta.core.agent.imagery.ImageGeneratorRegistry
import com.sherwood.wta.core.agent.plan.PlanManager
import com.sherwood.wta.core.agent.tool.builtin.AskUserTool
import com.sherwood.wta.core.agent.tool.builtin.BatchImportAppsTool
import com.sherwood.wta.core.agent.tool.builtin.BuildApkTool
import com.sherwood.wta.core.agent.tool.builtin.CheckPlayPolicyTool
import com.sherwood.wta.core.agent.tool.builtin.CheckAppHealthTool
import com.sherwood.wta.core.agent.tool.builtin.ClearAppCacheTool
import com.sherwood.wta.core.agent.tool.builtin.ClearRuntimeCacheTool
import com.sherwood.wta.core.agent.tool.builtin.CreateAppTool
import com.sherwood.wta.core.agent.tool.builtin.CreateModuleTool
import com.sherwood.wta.core.agent.tool.builtin.CreateShortcutTool
import com.sherwood.wta.core.agent.tool.builtin.DeleteAppTool
import com.sherwood.wta.core.agent.tool.builtin.DeleteEngineTool
import com.sherwood.wta.core.agent.tool.builtin.DeleteFileTool
import com.sherwood.wta.core.agent.tool.builtin.CloneAppTool
import com.sherwood.wta.core.agent.tool.builtin.DuplicateAppTool
import com.sherwood.wta.core.agent.tool.builtin.EditFileTool
import com.sherwood.wta.core.agent.tool.builtin.EnterPlanModeTool
import com.sherwood.wta.core.agent.tool.builtin.ExitPlanModeTool
import com.sherwood.wta.core.agent.tool.builtin.ExportAabTool
import com.sherwood.wta.core.agent.tool.builtin.ExportAppTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.ExportAppTool
import com.sherwood.wta.core.agent.tool.builtin.GetUsageStatsTool
import com.sherwood.wta.core.agent.tool.builtin.GetAdBlockStatusTool
import com.sherwood.wta.core.agent.tool.builtin.GetBuildEnvStatusTool
import com.sherwood.wta.core.agent.tool.builtin.GetAppTool
import com.sherwood.wta.core.agent.tool.builtin.InitializeBuildEnvTool
import com.sherwood.wta.core.agent.tool.builtin.GetEngineStatusTool
import com.sherwood.wta.core.agent.tool.builtin.GetModuleTool
import com.sherwood.wta.core.agent.tool.builtin.GetRuntimeStatusTool
import com.sherwood.wta.core.agent.tool.builtin.GlobTool
import com.sherwood.wta.core.agent.tool.builtin.GrepTool
import com.sherwood.wta.core.agent.tool.builtin.InstallRuntimeTool
import com.sherwood.wta.core.agent.tool.builtin.KillAllPortsTool
import com.sherwood.wta.core.agent.tool.builtin.KillPortTool
import com.sherwood.wta.core.agent.tool.builtin.ListInstalledAppsTool
import com.sherwood.wta.core.agent.tool.builtin.ListAppsTool
import com.sherwood.wta.core.agent.tool.builtin.ListFilesTool
import com.sherwood.wta.core.agent.tool.builtin.ListModulesTool
import com.sherwood.wta.core.agent.tool.builtin.ManageHostsRulesTool
import com.sherwood.wta.core.agent.tool.builtin.PreviewTool
import com.sherwood.wta.core.agent.tool.builtin.MoveToCategoryTool
import com.sherwood.wta.core.agent.tool.builtin.ReadFileTool
import com.sherwood.wta.core.agent.tool.builtin.ReadAppFileTool
import com.sherwood.wta.core.agent.tool.builtin.ScanPortsTool
import com.sherwood.wta.core.agent.tool.builtin.SelectEngineTool
import com.sherwood.wta.core.agent.tool.builtin.ShareApkTool
import com.sherwood.wta.core.agent.tool.builtin.TodoUpdateTool
import com.sherwood.wta.core.agent.tool.builtin.TodoWriteTool
import com.sherwood.wta.core.agent.tool.builtin.UpdateAppTool
import com.sherwood.wta.core.agent.tool.builtin.ListConfigTemplatesTool
import com.sherwood.wta.core.agent.tool.builtin.SaveConfigTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.ApplyConfigTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.ExportConfigTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.ImportConfigTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.DeleteConfigTemplateTool
import com.sherwood.wta.core.agent.tool.builtin.UpdateModuleTool
import com.sherwood.wta.core.agent.tool.builtin.WriteFileTool
import com.sherwood.wta.core.agent.tool.builtin.imagery.GenerateImageTool
import com.sherwood.wta.core.agent.tool.builtin.imagery.ListImagesTool
import com.sherwood.wta.core.agent.tool.builtin.imagery.ViewImageTool

class ToolRegistryFactory(
    private val planManager: PlanManager,
    private val imageRegistry: ImageGeneratorRegistry?
) {

    fun build(hasImageModel: Boolean): ToolRegistry {
        val base = baseTools()
        val plan = planTools()
        val imagery = if (hasImageModel && imageRegistry != null) imageryTools(imageRegistry) else emptyList()
        val all = (base + plan + imagery).distinctBy { it.name }
        return ToolRegistry(all)
    }

    /** In-app tools minus plan mode and image tools. Served by the local MCP. */
    fun buildForExternalHost(): ToolRegistry = ToolRegistry(baseTools())

    private fun baseTools(): List<Tool> = listOf(
        ReadFileTool(),
        ReadAppFileTool(),
        WriteFileTool(),
        EditFileTool(),
        DeleteFileTool(),
        ListFilesTool(),
        GlobTool(),
        GrepTool(),
        AskUserTool(),
        TodoWriteTool(),
        TodoUpdateTool(),
        ListAppsTool(),
        GetAppTool(),
        CreateAppTool(),
        UpdateAppTool(),
        // Common-config templates
        ListConfigTemplatesTool(),
        SaveConfigTemplateTool(),
        ApplyConfigTemplateTool(),
        ExportConfigTemplateTool(),
        ImportConfigTemplateTool(),
        DeleteConfigTemplateTool(),
        // App lifecycle
        BuildApkTool(),
        ShareApkTool(),
        ExportAppTool(),
        CreateShortcutTool(),
        MoveToCategoryTool(),
        ClearAppCacheTool(),
        DeleteAppTool(),
        DuplicateAppTool(),
        ExportAabTool(),
        // Ports & engine
        ScanPortsTool(),
        KillPortTool(),
        KillAllPortsTool(),
        GetEngineStatusTool(),
        SelectEngineTool(),
        DeleteEngineTool(),
        // Hosts ad-block & runtime management
        GetAdBlockStatusTool(),
        ManageHostsRulesTool(),
        GetRuntimeStatusTool(),
        InstallRuntimeTool(),
        ClearRuntimeCacheTool(),
        // Stats, modifier & import
        GetUsageStatsTool(),
        CheckAppHealthTool(),
        ListInstalledAppsTool(),
        CloneAppTool(),
        BatchImportAppsTool(),
        ExportAppTemplateTool(),
        // Build env & Play policy
        GetBuildEnvStatusTool(),
        InitializeBuildEnvTool(),
        CheckPlayPolicyTool(),
        // Modules
        ListModulesTool(),
        GetModuleTool(),
        CreateModuleTool(),
        UpdateModuleTool(),
        PreviewTool(),
    )

    private fun planTools(): List<Tool> = listOf(
        EnterPlanModeTool(planManager),
        ExitPlanModeTool(planManager),
    )

    private fun imageryTools(reg: ImageGeneratorRegistry): List<Tool> = listOf(
        GenerateImageTool(reg),
        ViewImageTool(),
        ListImagesTool(),
    )
}
