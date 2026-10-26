package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RunningHeadersTest {
    private val top = Box(0.14f, 0.05f, 0.3f, 0.09f)
    private fun page(header: String?, vararg blocks: LayoutBlock) = PageLayout(blocks.toList(), header = header)

    @Test
    fun `topptekst levert som overskrift flyttes til toppteksten`() {
        val pages = listOf(
            page("FASTEOPPLEGG", LayoutBlock(BlockType.PARAGRAPH, "a")),
            page("FASTEOPPLEGG", LayoutBlock(BlockType.PARAGRAPH, "b")),
            page(null, LayoutBlock(BlockType.HEADING, "FASTEOPPLEGG", box = top), LayoutBlock(BlockType.HEADING, "44 Periodisk faste"),
                LayoutBlock(BlockType.PARAGRAPH, "Det finnes ingen streng definisjon")),
            // Skilleside: bare overskrifter, røres ikke.
            page(null, LayoutBlock(BlockType.HEADING, "FASTEOPPLEGG", box = top)),
        )
        val out = RunningHeaders.strip(pages)
        assertEquals(listOf("44 Periodisk faste", "Det finnes ingen streng definisjon"), out[2].blocks.map { it.text })
        assertEquals("FASTEOPPLEGG", out[2].header)
        assertEquals(1, out[3].blocks.size)
    }

    @Test
    fun `overskrift som bare én annen side har som topptekst, beholdes`() {
        val pages = listOf(
            page("Forord", LayoutBlock(BlockType.PARAGRAPH, "a")),
            page(null, LayoutBlock(BlockType.HEADING, "Forord", box = top), LayoutBlock(BlockType.PARAGRAPH, "b")),
        )
        assertEquals("Forord", RunningHeaders.strip(pages)[1].blocks[0].text)
    }
}
