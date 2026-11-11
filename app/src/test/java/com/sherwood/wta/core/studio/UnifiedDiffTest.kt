package com.sherwood.wta.core.studio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnifiedDiffTest {

    @Test
    fun identicalInputReturnsEmpty() {
        assertThat(UnifiedDiff.diff("a\nb\n", "a\nb\n", "a/f", "b/f")).isEmpty()
    }

    @Test
    fun additionIsRendered() {
        val diff = UnifiedDiff.diff("a\nb\n", "a\nb\nc\n", "a/f", "b/f")
        assertThat(diff).contains("--- a/f")
        assertThat(diff).contains("+++ b/f")
        assertThat(diff).contains("+c")
    }

    @Test
    fun removalIsRendered() {
        val diff = UnifiedDiff.diff("a\nb\nc\n", "a\nc\n", "a/f", "b/f")
        assertThat(diff).contains("-b")
    }

    @Test
    fun replacementHasBothSides() {
        val diff = UnifiedDiff.diff("one\ntwo\nthree\n", "one\nTWO\nthree\n", "a/f", "b/f")
        assertThat(diff).contains("-two")
        assertThat(diff).contains("+TWO")
    }

    @Test
    fun contextLinesAreIncluded() {
        val old = (1..20).joinToString("\n") { "line$it" } + "\n"
        val new = (1..20).joinToString("\n") { if (it == 10) "changed" else "line$it" } + "\n"
        val diff = UnifiedDiff.diff(old, new, "a/f", "b/f", context = 2)
        assertThat(diff).contains(" line9")
        assertThat(diff).contains(" line11")
        assertThat(diff).doesNotContain(" line1\n")
    }

    @Test
    fun countsAddedAndRemovedLines() {
        val (added, removed) = UnifiedDiff.counts("a\nb\n", "a\nc\nd\n")
        assertThat(added).isEqualTo(2)
        assertThat(removed).isEqualTo(1)
    }

    @Test
    fun hunkHeaderIsWellFormed() {
        val diff = UnifiedDiff.diff("a\nb\nc\n", "a\nB\nc\n", "a/f", "b/f")
        assertThat(diff).contains("@@ -1,3 +1,3 @@")
    }

    @Test
    fun largeInputStillProducesADiff() {
        val old = (1..3000).joinToString("\n") { "o$it" }
        val new = (1..3000).joinToString("\n") { if (it == 1500) "changed" else "o$it" }
        val diff = UnifiedDiff.diff(old, new, "a/f", "b/f")
        assertThat(diff).contains("+changed")
    }
}
