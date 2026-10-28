package com.sherwood.wta.core.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RepoBridgeReviewTest {

    private val blocked = ReviewItem(
        "deploy/key.txt", ReviewStatus.ADDED, 40,
        listOf(SecretFinding("deploy/key.txt", SecretSeverity.BLOCK, "private_key"))
    )
    private val warned = ReviewItem(
        "config/.env", ReviewStatus.ADDED, 12,
        listOf(SecretFinding("config/.env", SecretSeverity.WARN, "env_file"))
    )
    private val edited = ReviewItem("edit.txt", ReviewStatus.MODIFIED, 8, emptyList())
    private val gone = ReviewItem("old.txt", ReviewStatus.DELETED, 0, emptyList())
    private val review = RepoBridgeReview(listOf(blocked, warned, edited, gone), "f")

    @Test
    fun `excluded paths leave the included set`() {
        val included = review.includedItems(setOf("config/.env", "old.txt"))
        assertThat(included.map { it.path }).containsExactly("deploy/key.txt", "edit.txt").inOrder()
    }

    @Test
    fun `an excluded blocking file no longer blocks`() {
        assertThat(review.blockingIncluded(emptySet()).map { it.path }).containsExactly("deploy/key.txt")
        assertThat(review.blockingIncluded(setOf("deploy/key.txt"))).isEmpty()
    }

    @Test
    fun `a warning counts as reviewed only when accepted and still included`() {
        assertThat(review.unreviewedWarnings(emptySet(), emptySet()).map { it.path })
            .containsExactly("config/.env")
        assertThat(review.unreviewedWarnings(emptySet(), setOf("config/.env"))).isEmpty()
        assertThat(review.unreviewedWarnings(setOf("config/.env"), emptySet())).isEmpty()
    }

    @Test
    fun `filters select by status and flags`() {
        assertThat(review.items.filtered(ReviewFilter.ALL)).hasSize(4)
        assertThat(review.items.filtered(ReviewFilter.ADDED).map { it.path })
            .containsExactly("deploy/key.txt", "config/.env").inOrder()
        assertThat(review.items.filtered(ReviewFilter.MODIFIED).map { it.path }).containsExactly("edit.txt")
        assertThat(review.items.filtered(ReviewFilter.DELETED).map { it.path }).containsExactly("old.txt")
        assertThat(review.items.filtered(ReviewFilter.FLAGGED).map { it.path })
            .containsExactly("deploy/key.txt", "config/.env").inOrder()
    }

    @Test
    fun `fingerprint is independent of input order`() {
        val a = listOf(
            Triple(ReviewStatus.ADDED, "b.txt", "1".repeat(40)),
            Triple(ReviewStatus.MODIFIED, "a.txt", "2".repeat(40))
        )
        assertThat(reviewFingerprint(a)).isEqualTo(reviewFingerprint(a.reversed()))
    }

    @Test
    fun `fingerprint changes when a status or a blob changes`() {
        val base = listOf(Triple(ReviewStatus.MODIFIED, "a.txt", "1".repeat(40)))
        val otherStatus = listOf(Triple(ReviewStatus.ADDED, "a.txt", "1".repeat(40)))
        val otherBlob = listOf(Triple(ReviewStatus.MODIFIED, "a.txt", "2".repeat(40)))
        assertThat(reviewFingerprint(base)).isNotEqualTo(reviewFingerprint(otherStatus))
        assertThat(reviewFingerprint(base)).isNotEqualTo(reviewFingerprint(otherBlob))
    }

    @Test
    fun `paths are wrapped in a first-strong isolate with a matching pop`() {
        val wrapped = isolatedPath("assets/app.js")
        assertThat(wrapped.first()).isEqualTo('\u2068')
        assertThat(wrapped.last()).isEqualTo('\u2069')
        assertThat(wrapped.substring(1, wrapped.length - 1)).isEqualTo("assets/app.js")
    }
}
