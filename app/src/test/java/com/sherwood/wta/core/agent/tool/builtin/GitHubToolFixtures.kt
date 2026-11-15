package com.sherwood.wta.core.agent.tool.builtin

import android.content.Context
import com.sherwood.wta.core.agent.files.ProjectFileManager
import com.sherwood.wta.core.agent.permission.PermissionPrompter
import com.sherwood.wta.core.agent.todo.TodoManager
import com.sherwood.wta.core.agent.tool.ToolContext
import com.sherwood.wta.data.dao.WebAppDao
import com.sherwood.wta.data.dao.WebAppStartupCandidate
import com.sherwood.wta.data.dao.WebAppSummary
import com.sherwood.wta.data.model.AiModel
import com.sherwood.wta.data.model.AiProvider
import com.sherwood.wta.data.model.ApiKeyConfig
import com.sherwood.wta.data.model.AppType
import com.sherwood.wta.data.model.ModelCapability
import com.sherwood.wta.data.model.SavedModel
import com.sherwood.wta.data.model.WebApp
import com.sherwood.wta.data.repository.WebAppRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared fixtures for the git agent tool tests.
 *
 * A tool only reaches the network through the stored credential, so a context with no
 * token lets every "not connected" assertion run without touching it.
 */
internal fun toolContext(
    context: Context,
    prompter: PermissionPrompter,
    sessionId: String = "github-tool-test"
) = ToolContext(
    androidContext = context,
    sessionId = sessionId,
    fileManager = ProjectFileManager(context),
    textModel = SavedModel(
        model = AiModel(id = "m", name = "m", provider = AiProvider.OPENAI),
        apiKeyId = "k",
        capabilities = listOf(ModelCapability.TEXT)
    ),
    textApiKey = ApiKeyConfig(provider = AiProvider.OPENAI, apiKey = "key"),
    prompter = prompter,
    todos = TodoManager(),
    appRepository = WebAppRepository(FakeWebAppDao())
)

/** The repository eagerly reads three Flow properties at construction; the rest is never called. */
internal class FakeWebAppDao : WebAppDao {
    override fun getAllWebApps(): Flow<List<WebApp>> = flowOf(emptyList())
    override fun getAllWebAppSummaries(): Flow<List<WebAppSummary>> = flowOf(emptyList())
    override fun getHttpWebApps(appType: AppType): Flow<List<WebApp>> = flowOf(emptyList())
    override suspend fun getStartupCandidatesWithoutIcons(appType: AppType, limit: Int): List<WebAppStartupCandidate> = throw NotImplementedError()
    override suspend fun getWebAppById(id: Long): WebApp? = throw NotImplementedError()
    override fun getWebAppByIdFlow(id: Long): Flow<WebApp?> = throw NotImplementedError()
    override suspend fun insert(webApp: WebApp): Long = throw NotImplementedError()
    override suspend fun update(webApp: WebApp) = throw NotImplementedError()
    override suspend fun delete(webApp: WebApp) = throw NotImplementedError()
    override suspend fun deleteById(id: Long) = throw NotImplementedError()
    override suspend fun updateActivationStatus(id: Long, activated: Boolean) = throw NotImplementedError()
    override suspend fun upgradeRemoteHttpUrls(appType: AppType, updatedAt: Long): Int = throw NotImplementedError()
    override suspend fun getCount(): Int = throw NotImplementedError()
    override fun searchWebApps(query: String): Flow<List<WebApp>> = throw NotImplementedError()
    override suspend fun insertAll(webApps: List<WebApp>): List<Long> = throw NotImplementedError()
    override suspend fun updateAll(webApps: List<WebApp>) = throw NotImplementedError()
    override suspend fun deleteAll(webApps: List<WebApp>) = throw NotImplementedError()
    override suspend fun deleteByIds(ids: List<Long>) = throw NotImplementedError()
    override suspend fun updateActivationStatusBatch(ids: List<Long>, activated: Boolean) = throw NotImplementedError()
    override suspend fun getWebAppsByIds(ids: List<Long>): List<WebApp> = throw NotImplementedError()
    override fun getActivatedWebApps(): Flow<List<WebApp>> = throw NotImplementedError()
    override fun getRecentWebApps(limit: Int): Flow<List<WebApp>> = throw NotImplementedError()
    override suspend fun countByName(name: String, excludeId: Long): Int = throw NotImplementedError()
    override suspend fun clearCategoryId(categoryId: Long) = throw NotImplementedError()
    override suspend fun getAllUrls(): List<String> = throw NotImplementedError()
    override suspend fun updateHomeSortIndex(id: Long, index: Int) = throw NotImplementedError()
}
