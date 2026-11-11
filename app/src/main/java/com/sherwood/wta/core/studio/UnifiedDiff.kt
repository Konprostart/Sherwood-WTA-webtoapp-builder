package com.sherwood.wta.core.studio

/**
 * A small, dependency-free unified diff.
 *
 * The Studio shows what a pull/checkout would change before the working copy is
 * touched. Files here are source files (mostly a few thousand lines), so a bounded
 * LCS is enough; anything larger than [MAX_LCS_CELLS] degrades to one replace block
 * instead of allocating an enormous table.
 */
object UnifiedDiff {

    private const val MAX_LCS_CELLS = 2_000_000
    private const val MAX_OUTPUT_LINES = 4_000

    private enum class OpType { EQUAL, DELETE, INSERT }

    private data class Op(val type: OpType, val line: String)

    /**
     * Returns a unified diff with three lines of context, or an empty string when the
     * inputs are identical. Paths are rendered as given (callers pass `a/…`/`b/…`).
     */
    fun diff(
        oldText: String,
        newText: String,
        oldPath: String,
        newPath: String,
        context: Int = 3
    ): String {
        if (oldText == newText) return ""
        val oldLines = splitLines(oldText)
        val newLines = splitLines(newText)
        val ops = operations(oldLines, newLines)
        if (ops.none { it.type != OpType.EQUAL }) return ""

        val sb = StringBuilder()
        sb.append("--- ").append(oldPath).append('\n')
        sb.append("+++ ").append(newPath).append('\n')
        appendHunks(sb, ops, context.coerceIn(1, 20))
        val text = sb.toString()
        return if (text.lineSequence().count() > MAX_OUTPUT_LINES) {
            text.lineSequence().take(MAX_OUTPUT_LINES).joinToString("\n") +
                "\n… diff truncated …\n"
        } else {
            text
        }
    }

    /** Number of added and removed lines, for a compact status summary. */
    fun counts(oldText: String, newText: String): Pair<Int, Int> {
        if (oldText == newText) return 0 to 0
        val ops = operations(splitLines(oldText), splitLines(newText))
        return ops.count { it.type == OpType.INSERT } to ops.count { it.type == OpType.DELETE }
    }

    private fun appendHunks(sb: StringBuilder, ops: List<Op>, context: Int) {
        // Indexes of changed operations.
        val changed = ops.indices.filter { ops[it].type != OpType.EQUAL }
        var cursor = 0
        while (cursor < changed.size) {
            val start = (changed[cursor] - context).coerceAtLeast(0)
            var end = changed[cursor]
            while (cursor + 1 < changed.size && changed[cursor + 1] - end <= context * 2 + 1) {
                cursor++
                end = changed[cursor]
            }
            val last = (end + context).coerceAtMost(ops.lastIndex)
            appendHunk(sb, ops, start, last)
            cursor++
        }
    }

    private fun appendHunk(sb: StringBuilder, ops: List<Op>, start: Int, end: Int) {
        var oldLine = 1
        var newLine = 1
        for (i in 0 until start) {
            when (ops[i].type) {
                OpType.EQUAL -> { oldLine++; newLine++ }
                OpType.DELETE -> oldLine++
                OpType.INSERT -> newLine++
            }
        }
        val oldStart = oldLine
        val newStart = newLine
        var oldCount = 0
        var newCount = 0
        for (i in start..end) {
            when (ops[i].type) {
                OpType.EQUAL -> { oldCount++; newCount++ }
                OpType.DELETE -> oldCount++
                OpType.INSERT -> newCount++
            }
        }
        sb.append("@@ -").append(oldStart).append(',').append(oldCount)
            .append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
        for (i in start..end) {
            val op = ops[i]
            when (op.type) {
                OpType.EQUAL -> sb.append(' ').append(op.line).append('\n')
                OpType.DELETE -> sb.append('-').append(op.line).append('\n')
                OpType.INSERT -> sb.append('+').append(op.line).append('\n')
            }
        }
    }

    /**
     * LCS edit script with a cell cap. For huge inputs the middle section becomes a
     * delete-all/insert-all block, which is still correct (just not minimal).
     */
    private fun operations(oldLines: List<String>, newLines: List<String>): List<Op> {
        // Trim the common prefix and suffix first: real edits are usually local.
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size &&
            oldLines[prefix] == newLines[prefix]
        ) {
            prefix++
        }
        var oldSuffix = oldLines.size
        var newSuffix = newLines.size
        while (oldSuffix > prefix && newSuffix > prefix &&
            oldLines[oldSuffix - 1] == newLines[newSuffix - 1]
        ) {
            oldSuffix--
            newSuffix--
        }

        val result = ArrayList<Op>(oldLines.size + newLines.size)
        for (i in 0 until prefix) result.add(Op(OpType.EQUAL, oldLines[i]))

        val a = oldLines.subList(prefix, oldSuffix)
        val b = newLines.subList(prefix, newSuffix)
        if (a.isEmpty()) {
            b.forEach { result.add(Op(OpType.INSERT, it)) }
        } else if (b.isEmpty()) {
            a.forEach { result.add(Op(OpType.DELETE, it)) }
        } else if (a.size.toLong() * b.size.toLong() > MAX_LCS_CELLS) {
            a.forEach { result.add(Op(OpType.DELETE, it)) }
            b.forEach { result.add(Op(OpType.INSERT, it)) }
        } else {
            result.addAll(lcsOperations(a, b))
        }

        for (i in oldSuffix until oldLines.size) {
            result.add(Op(OpType.EQUAL, oldLines[i]))
        }
        return result
    }

    private fun lcsOperations(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        // lengths[i][j] = LCS length of a[i..] and b[j..]; IntArray rows keep memory low.
        val lengths = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i][j] = if (a[i] == b[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops.add(Op(OpType.EQUAL, a[i])); i++; j++ }
                lengths[i + 1][j] >= lengths[i][j + 1] -> { ops.add(Op(OpType.DELETE, a[i])); i++ }
                else -> { ops.add(Op(OpType.INSERT, b[j])); j++ }
            }
        }
        while (i < n) { ops.add(Op(OpType.DELETE, a[i])); i++ }
        while (j < m) { ops.add(Op(OpType.INSERT, b[j])); j++ }
        return ops
    }

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')
}
