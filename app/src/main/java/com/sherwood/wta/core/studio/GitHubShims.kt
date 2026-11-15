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
 * Temporary bridge between the pre-split GitHub type names and the provider-neutral
 * model in [com.sherwood.wta.core.studio.git].
 *
 * The Studio's services now speak the neutral vocabulary. The screens and the agent
 * tools are still written against the old names, and a type alias keeps them
 * compiling without a 600-line rename that would bury the actual seam in noise.
 * The alias resolves to the same class, so there is no conversion, no copying and no
 * behaviour difference.
 *
 * These aliases go away in the release that introduces the first non-GitHub driver:
 * from then on the UI has to name a provider-independent type anyway, and a lingering
 * `GitHubRepository` next to a Gitea server would be actively misleading.
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
