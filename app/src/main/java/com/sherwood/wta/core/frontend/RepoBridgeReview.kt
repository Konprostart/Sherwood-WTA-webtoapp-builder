package com.sherwood.wta.core.frontend

import java.security.MessageDigest

/** What a push would do to one path. */
enum class ReviewStatus { ADDED, MODIFIED, DELETED }

/**
 * One reviewable path. [findings] only exist for added and modified files, because a
 * deletion uploads no content. [sizeBytes] is the local size (0 for deletions).
 */
data class ReviewItem(
    val path: String,
    val status: ReviewStatus,
    val sizeBytes: Long,
    val findings: List<SecretFinding>
) {
    val hasBlocking: Boolean get() = findings.any { it.severity == SecretSeverity.BLOCK }
    val hasWarning: Boolean get() = findings.any { it.severity == SecretSeverity.WARN }
}

/**
 * The full set of changes a push would make, plus a [fingerprint] over every path, status
 * and blob sha. The fingerprint is what the user reviewed: if the working copy changes
 * afterwards, the fingerprint changes too and the commit is refused.
 */
data class RepoBridgeReview(
    val items: List<ReviewItem>,
    val fingerprint: String
)

/**
 * The user's answer to a review.
 *
 * [fingerprint] must match the state at commit time. [excluded] paths are left out of the
 * push: an excluded new file is not uploaded, and an excluded changed or deleted file keeps
 * its version on GitHub. [acceptedWarnings] must cover every warned path that is still in
 * the push; blocking findings cannot be accepted.
 */
data class ReviewDecision(
    val fingerprint: String,
    val excluded: Set<String> = emptySet(),
    val acceptedWarnings: Set<String> = emptySet()
)

/**
 * SHA-256 over the sorted (status, path, blob sha) triples. Deterministic for the same
 * working copy and base, so it can be compared between preview and commit.
 */
fun reviewFingerprint(entries: Collection<Triple<ReviewStatus, String, String>>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.sortedWith(compareBy({ it.second }, { it.first.name })).forEach { (status, path, sha) ->
        digest.update("${status.name}\t$path\t$sha\n".toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
