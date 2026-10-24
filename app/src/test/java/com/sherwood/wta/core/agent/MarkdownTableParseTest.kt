package com.sherwood.wta.core.agent

import com.sherwood.wta.ui.agent.components.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableParseTest {

    @Test
    fun `pipe table parses into header and rows`() {
        val text = """
            | Name | Age |
            | --- | --- |
            | Alice | 30 |
            | Bob | 25 |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(text)

        assertEquals(1, blocks.size)
        val table = blocks[0] as com.sherwood.wta.ui.agent.components.MdBlock.Table
        assertEquals(listOf("Name", "Age"), table.header)
        assertEquals(listOf(listOf("Alice", "30"), listOf("Bob", "25")), table.rows)
    }

    @Test
    fun `separator row with alignment colons is accepted`() {
        val text = "| A | B |\n| :--- | ---: |\n| 1 | 2 |"
        val table = parseMarkdownBlocks(text).single() as com.sherwood.wta.ui.agent.components.MdBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `rows are normalized to header width`() {
        val text = "| A | B | C |\n| --- | --- | --- |\n| 1 |"
        val table = parseMarkdownBlocks(text).single() as com.sherwood.wta.ui.agent.components.MdBlock.Table
        assertEquals(listOf("1", "", ""), table.rows[0])
    }

    @Test
    fun `pipe-looking text without separator stays a paragraph`() {
        val text = "| not | a |\n| table at all |"
        val blocks = parseMarkdownBlocks(text)
        assertTrue(blocks.all { it is com.sherwood.wta.ui.agent.components.MdBlock.Paragraph })
    }

    @Test
    fun `table interrupts and resumes surrounding content`() {
        val text = """
            Intro paragraph.

            | H |
            | --- |
            | x |

            # Heading after
        """.trimIndent()

        val blocks = parseMarkdownBlocks(text)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is com.sherwood.wta.ui.agent.components.MdBlock.Paragraph)
        assertTrue(blocks[1] is com.sherwood.wta.ui.agent.components.MdBlock.Table)
        assertTrue(blocks[2] is com.sherwood.wta.ui.agent.components.MdBlock.Heading)
    }
}
