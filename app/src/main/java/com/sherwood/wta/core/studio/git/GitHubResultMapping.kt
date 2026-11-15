package com.sherwood.wta.core.studio.git

import com.sherwood.wta.core.frontend.GitDataFailureKind
import com.sherwood.wta.core.frontend.GitDataResult

/**
 * Translation between the GitHub-specific result types and the neutral ones.
 *
 * The GitHub client predates the provider seam and reports its failures with its own
 * enum. Rather than duplicate that client, the driver maps in one place — so a caller
 * only ever sees [GitResult] / [GitFailureKind], and no GitHub-specific type escapes the
 * driver.
 */
internal fun GitDataFailureKind.toGitFailureKind(): GitFailureKind = when (this) {
    GitDataFailureKind.NO_TOKEN -> GitFailureKind.NO_TOKEN
    GitDataFailureKind.UNAUTHORIZED -> GitFailureKind.UNAUTHORIZED
    GitDataFailureKind.FORBIDDEN -> GitFailureKind.FORBIDDEN
    GitDataFailureKind.RATE_LIMITED -> GitFailureKind.RATE_LIMITED
    GitDataFailureKind.NOT_FOUND -> GitFailureKind.NOT_FOUND
    GitDataFailureKind.CONFLICT -> GitFailureKind.CONFLICT
    GitDataFailureKind.UNPROCESSABLE -> GitFailureKind.UNPROCESSABLE
    GitDataFailureKind.NETWORK -> GitFailureKind.NETWORK
    GitDataFailureKind.UNEXPECTED -> GitFailureKind.UNEXPECTED
    GitDataFailureKind.HTTP -> GitFailureKind.HTTP
}

/**
 * Maps a failure without tying the result type to whatever the GitHub call returned.
 *
 * [GitResult.Failure] is a `GitResult<Nothing>`, so the caller can return it from a
 * function whose success type is different — which is exactly what a failure branch does.
 */
internal fun GitDataResult.Failure.toGitFailure(): GitResult.Failure =
    GitResult.Failure(kind = kind.toGitFailureKind(), statusCode = statusCode, message = message)

internal fun <T> GitDataResult<T>.toGitResult(): GitResult<T> = when (this) {
    is GitDataResult.Success -> GitResult.Success(value)
    is GitDataResult.Failure -> toGitFailure()
}
