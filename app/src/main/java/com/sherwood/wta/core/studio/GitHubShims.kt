package com.sherwood.wta.core.studio

import com.sherwood.wta.core.studio.git.GitAccount
import com.sherwood.wta.core.studio.git.GitArtifact
import com.sherwood.wta.core.studio.git.GitBranch
import com.sherwood.wta.core.studio.git.GitChangeRequest
import com.sherwood.wta.core.studio.git.GitChangeRequestDetails
import com.sherwood.wta.core.studio.git.GitChangeRequestFile
import com.sherwood.wta.core.studio.git.GitChangeRequestReview
import com.sherwood.wta.core.studio.git.GitChangeRequestReviewComment
import com.sherwood.wta.core.studio.git.GitCheck
import com.sherwood.wta.core.studio.git.GitCheckSummary
import com.sherwood.wta.core.studio.git.GitCommitSummary
import com.sherwood.wta.core.studio.git.GitFileContent
import com.sherwood.wta.core.studio.git.GitMergeResult
import com.sherwood.wta.core.studio.git.GitPipeline
import com.sherwood.wta.core.studio.git.GitPipelineRun
import com.sherwood.wta.core.studio.git.GitRelease
import com.sherwood.wta.core.studio.git.GitReleaseAsset
import com.sherwood.wta.core.studio.git.GitRepository
import com.sherwood.wta.core.studio.git.GitRunJob
import com.sherwood.wta.core.studio.git.GitRunStep

/**
 * The GitHub transport's vocabulary, kept as aliases of the provider-neutral model in
 * [com.sherwood.wta.core.studio.git].
 *
 * `GitHubApiClient` is the GitHub driver and names its own concepts accordingly, and the
 * publish path plus a few older tests still read those names. An alias resolves to the
 * same class, so there is no conversion, no copying and no behaviour difference.
 *
 * The Studio's screens and services do **not** use these names any more: with a second
 * driver in the build, a `GitHubRepository` next to a Gitea server would be actively
 * misleading, so the UI speaks `GitRepository`, `GitChangeRequest`, `GitPipelineRun` and
 * so on. Anything reachable only through these aliases is GitHub-specific code.
 *
 * `GitResult` and `GitFailureKind` are deliberately absent: Kotlin does not let a
 * type alias qualify its nested classes (`Alias.Success`), so those two names were
 * renamed at every call site instead.
 */
typealias GitHubUser = GitAccount
typealias GitHubRepository = GitRepository
typealias GitHubBranch = GitBranch
typealias GitHubPullRequest = GitChangeRequest
typealias GitHubPullRequestDetails = GitChangeRequestDetails
typealias GitHubPullRequestFile = GitChangeRequestFile
typealias GitHubPullRequestReview = GitChangeRequestReview
typealias GitHubPullRequestReviewComment = GitChangeRequestReviewComment
typealias GitHubCheckRun = GitCheck
typealias GitHubCheckSummary = GitCheckSummary
typealias GitHubCommitSummary = GitCommitSummary
typealias GitHubMergeResult = GitMergeResult
typealias GitHubWorkflow = GitPipeline
typealias GitHubWorkflowRun = GitPipelineRun
typealias GitHubJobStep = GitRunStep
typealias GitHubJob = GitRunJob
typealias GitHubArtifact = GitArtifact
typealias GitHubReleaseAsset = GitReleaseAsset
typealias GitHubRelease = GitRelease
typealias GitHubFileContent = GitFileContent
