package com.sherwood.wta.core.studio.git

/**
 * Reads a unified-diff patch to find the *new-side* line numbers a review comment may
 * target.
 *
 * Removed lines do not exist on the new side and are skipped, so every number returned is
 * one the host accepts when the comment is anchored to the right ("new") side. Hosts omit
 * the patch for binary and oversized files, in which case the list is empty and the caller
 * falls back to manual line entry instead of guessing.
 */
object GitDiffLines {

    fun commentableNewLines(patch: String?, limit: Int = 40): List<Int> {
        if (patch.isNullOrBlank() || limit <= 0) return emptyList()
        val result = ArrayList<Int>()
        var newLine = 0
        for (raw in patch.split('\n')) {
            if (raw.startsWith("@@")) {
                // Hunk header: @@ -oldStart[,oldCount] +newStart[,newCount] @@
                val plus = raw.indexOf('+')
                if (plus < 0) continue
                newLine = raw.substring(plus + 1).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                continue
            }
            if (newLine <= 0) continue
            when {
                raw.startsWith("+") -> {
                    result.add(newLine)
                    newLine++
                }
                raw.startsWith("-") -> Unit   // gone on the new side
                raw.startsWith("\\") -> Unit  // "\ No newline at end of file"
                else -> newLine++             // context line
            }
            if (result.size >= limit) break
        }
        return result
    }
}
