package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StudioPureLogicTest {

    @Test
    fun branchNames() {
        assertThat(WorkspaceService.isValidBranchName("main")).isTrue()
        assertThat(WorkspaceService.isValidBranchName("feat/studio")).isTrue()
        assertThat(WorkspaceService.isValidBranchName("a..b")).isFalse()
        assertThat(WorkspaceService.isValidBranchName("/leading")).isFalse()
        assertThat(WorkspaceService.isValidBranchName("trailing/")).isFalse()
        assertThat(WorkspaceService.isValidBranchName("has space")).isFalse()
        assertThat(WorkspaceService.isValidBranchName("x.lock")).isFalse()
    }

    @Test
    fun repoPathsRejectTraversal() {
        assertThat(WorkspaceService.normalizeRepoPath("src/app/Main.kt")).isEqualTo("src/app/Main.kt")
        assertThat(WorkspaceService.normalizeRepoPath("../secret")).isNull()
        assertThat(WorkspaceService.normalizeRepoPath("/etc/passwd")).isNull()
        assertThat(WorkspaceService.normalizeRepoPath("a//b")).isNull()
        assertThat(WorkspaceService.normalizeRepoPath("a\\b")).isEqualTo("a/b")
    }

    @Test
    fun fileNamesAreSanitized() {
        assertThat(ArtifactVault.sanitizeFileName("../../evil.apk")).isEqualTo("evil.apk")
        assertThat(ArtifactVault.sanitizeFileName("my app (1).apk")).isEqualTo("my_app__1_.apk")
        assertThat(ArtifactVault.sanitizeFileName("")).isEqualTo("artifact.apk")
    }

    @Test
    fun artifactKindDetection() {
        assertThat(ArtifactVault.detectKind("app.apk")).isEqualTo(ArtifactKind.APK)
        assertThat(ArtifactVault.detectKind("app.aab")).isEqualTo(ArtifactKind.AAB)
        assertThat(ArtifactVault.detectKind("app.apk.txt")).isEqualTo(ArtifactKind.UNKNOWN)
    }

    @Test
    fun sha256KnownValue() {
        assertThat(ArtifactVault.sha256("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun manifestValidation() {
        assertThat(WorkspaceManifest(project = "acme/demo", branch = "main").isValid()).isTrue()
        assertThat(WorkspaceManifest(project = "no-slash", branch = "main").isValid()).isFalse()
        assertThat(WorkspaceManifest(project = "acme/demo", branch = " ").isValid()).isFalse()
        assertThat(WorkspaceManifest(project = "acme/demo", branch = "main", schema = 2).isValid())
            .isFalse()
    }

    @Test
    fun noticesCoverCoreComponentsAndNoForbiddenLicences() {
        val components = ThirdPartyNotices.components
        assertThat(components).isNotEmpty()
        assertThat(components.map { it.name }).contains("AndroidX (core, appcompat, activity, lifecycle, room, datastore, webkit, documentfile)")
        assertThat(components.map { it.name }).contains("OkHttp and okhttp-dnsoverhttps")
        val forbidden = components.filter {
            val licence = it.licence.uppercase()
            licence.contains("GPL") && !licence.contains("MPL")
        }
        assertThat(forbidden).isEmpty()
        assertThat(components.first { it.name == "JGit" }.note).contains("NOT SHIPPED")
    }
}
