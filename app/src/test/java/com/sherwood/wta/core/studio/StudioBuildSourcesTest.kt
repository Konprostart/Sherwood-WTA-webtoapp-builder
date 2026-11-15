package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.sherwood.wta.core.studio.git.GitPipeline
import com.sherwood.wta.core.studio.git.GitPipelineRun
import org.junit.Test

/**
 * Release-asset parsing, dispatch-run selection and the vault export name.
 *
 * These are the pure decisions behind "download a published release" and "build this
 * branch": what counts as a downloadable asset, which run a dispatch produced, and
 * what the saved file is called. No network, no Android runtime.
 */
class StudioBuildSourcesTest {

    private fun json(text: String) = JsonParser.parseString(text)

    private fun workflow(id: String = "1", path: String = ".github/workflows/android-ci.yml") =
        GitPipeline(id = id, name = "Android CI Build", path = path, state = "active")

    private fun run(
        id: Long,
        workflowId: String = "1",
        workflowPath: String? = ".github/workflows/android-ci.yml",
        status: String = "queued"
    ) = GitPipelineRun(
        id = id,
        name = "Android CI Build",
        workflowId = workflowId,
        workflowPath = workflowPath,
        status = status,
        conclusion = null,
        branch = "feat/studio",
        headSha = "abc",
        event = "workflow_dispatch",
        runNumber = 1,
        displayTitle = null,
        createdAt = null,
        updatedAt = null,
        htmlUrl = null
    )

    // ------------------------------------------------------------ releases

    @Test
    fun parsesReleaseWithAssets() {
        val release = GitHubApiClient.parseRelease(
            json(
                """{"id":7,"tag_name":"v2.9.3","name":"2.9.3","draft":false,
                   "prerelease":true,"published_at":"2026-01-01T00:00:00Z",
                   "html_url":"https://github.com/o/r/releases/tag/v2.9.3",
                   "assets":[
                     {"id":11,"name":"app.apk","size":1234,
                      "content_type":"application/vnd.android.package-archive",
                      "url":"https://api.github.com/repos/o/r/releases/assets/11",
                      "browser_download_url":"https://github.com/o/r/releases/download/v2.9.3/app.apk",
                      "state":"uploaded"},
                     {"id":12,"name":"app.aab","size":4321,
                      "url":"https://api.github.com/repos/o/r/releases/assets/12",
                      "state":"open"}
                   ]}"""
            )
        )
        requireNotNull(release)
        assertThat(release.tagName).isEqualTo("v2.9.3")
        assertThat(release.name).isEqualTo("2.9.3")
        assertThat(release.draft).isFalse()
        assertThat(release.prerelease).isTrue()
        assertThat(release.assets).hasSize(2)
        assertThat(release.assets[0].sizeInBytes).isEqualTo(1234L)
        // The API URL is what carries a token; the browser URL is not stored.
        assertThat(release.assets[0].apiDownloadUrl)
            .isEqualTo("https://api.github.com/repos/o/r/releases/assets/11")
        assertThat(release.assets[0].isDownloadable).isTrue()
        assertThat(release.assets[1].isDownloadable).isFalse()
    }

    @Test
    fun releaseWithoutTagIsRejected() {
        assertThat(GitHubApiClient.parseRelease(json("""{"id":1,"name":"x"}"""))).isNull()
    }

    @Test
    fun releaseAssetWithoutApiUrlIsRejected() {
        // A browser_download_url alone cannot be authenticated for a private repo, so
        // an asset that only has one is not usable and must not be offered.
        val asset = GitHubApiClient.parseReleaseAsset(
            json(
                """{"id":3,"name":"app.apk","size":10,
                   "browser_download_url":"https://github.com/o/r/releases/download/v1/app.apk"}"""
            )
        )
        assertThat(asset).isNull()
    }

    @Test
    fun releaseAssetStateDefaultsToDownloadableWhenAbsent() {
        val asset = GitHubApiClient.parseReleaseAsset(
            json(
                """{"id":4,"name":"app.apk","size":10,
                   "url":"https://api.github.com/repos/o/r/releases/assets/4"}"""
            )
        )
        requireNotNull(asset)
        assertThat(asset.state).isNull()
        assertThat(asset.isDownloadable).isTrue()
    }

    // ------------------------------------------------- dispatched-run selection

    @Test
    fun selectsOnlyTheNewRunOfTheDispatchedWorkflow() {
        val candidates = listOf(
            run(id = 100, workflowId = 1),
            run(id = 101, workflowId = 2),
            run(id = 102, workflowId = 1)
        )
        // 100 existed before the dispatch; 101 belongs to a different workflow.
        val selected = BuildStudio.selectDispatchedRun(candidates, setOf(100L), workflow("1"))
        assertThat(selected?.id).isEqualTo(102L)
    }

    @Test
    fun returnsNullWhileTheDispatchedRunIsNotListedYet() {
        val candidates = listOf(run(id = 100, workflowId = 1))
        val selected = BuildStudio.selectDispatchedRun(candidates, setOf(100L), workflow("1"))
        assertThat(selected).isNull()
    }

    @Test
    fun ignoresRunsOfOtherWorkflowsStartedAfterTheDispatch() {
        val candidates = listOf(
            run(id = 200, workflowId = 9),
            run(id = 201, workflowId = 9)
        )
        assertThat(BuildStudio.selectDispatchedRun(candidates, emptySet(), workflow("1")))
            .isNull()
    }

    @Test
    fun matchesAHostThatNamesTheWorkflowFileInsteadOfItsId() {
        // Gitea/Forgejo runs carry no workflow id, only the workflow path.
        val candidates = listOf(
            run(id = 300, workflowId = "", workflowPath = ".gitea/workflows/build.yml"),
            run(id = 301, workflowId = "", workflowPath = ".gitea/workflows/release.yml")
        )
        val selected = BuildStudio.selectDispatchedRun(
            candidates,
            knownRunIds = setOf(300L),
            workflow = workflow(id = "release", path = ".gitea/workflows/release.yml")
        )
        assertThat(selected?.id).isEqualTo(301L)
    }

    // ------------------------------------------------------------ export name

    @Test
    fun exportNameStripsTheVaultIdSuffix() {
        val artifact = artifact(
            id = "1a2b3c4d5e6f-1700000000000",
            fileName = "app-standard-debug-1a2b3c4d5e6f-1700000000000.apk"
        )
        assertThat(ArtifactExport.exportName(artifact)).isEqualTo("app-standard-debug.apk")
    }

    @Test
    fun exportNameKeepsABundleExtension() {
        val artifact = artifact(
            id = "aabbccddeeff-1700000009999",
            fileName = "Sherwood-WTA-2.9.3-gplay-aabbccddeeff-1700000009999.aab"
        )
        assertThat(ArtifactExport.exportName(artifact)).isEqualTo("Sherwood-WTA-2.9.3-gplay.aab")
    }

    @Test
    fun exportNameStaysUsableWhenTheSuffixIsMissing() {
        // Older records (or a renamed file) must still produce a safe name.
        val artifact = artifact(id = "zzz-1", fileName = "already-clean.apk")
        assertThat(ArtifactExport.exportName(artifact)).isEqualTo("already-clean.apk")
    }

    @Test
    fun exportMimeTypeMatchesTheArtifactKind() {
        assertThat(ArtifactExport.mimeType(artifact(id = "x", fileName = "a.apk")))
            .isEqualTo(ArtifactExport.APK_MIME)
        assertThat(ArtifactExport.mimeType(artifact(id = "x", fileName = "a.aab")))
            .isEqualTo(ArtifactExport.BUNDLE_MIME)
    }

    private fun artifact(id: String, fileName: String) = VaultArtifact(
        id = id,
        fileName = fileName,
        kind = ArtifactVault.detectKind(fileName).name,
        sizeBytes = 1L,
        sha256 = "0".repeat(64)
    )
}
