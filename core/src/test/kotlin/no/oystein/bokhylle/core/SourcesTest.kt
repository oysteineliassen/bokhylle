package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourcesTest {
    private fun para(t: String) = LayoutBlock(BlockType.PARAGRAPH, t)
    private fun note(n: Int) = LayoutBlock(BlockType.FOOTNOTE, "Kilde nummer $n", number = n.toString())

    private val text = PageLayout(listOf(para("Akrylamid i potetgull.[^3,4] Og mer.[^6–8]"), LayoutBlock(BlockType.FOOTNOTE, "Egen fotnote", number = "a")))
    private val list1 = PageLayout(listOf(LayoutBlock(BlockType.HEADING, "Kilder")) + (1..5).map { para("$it. Kilde nummer $it") })
    private val list2 = PageLayout((6..10).map(::note))
    private val book = listOf(text, list1, list2)

    @Test
    fun `kildeliste levert som fotnoter blir synlige avsnitt`() {
        val out = Sources.normalize(book)
        assertEquals(listOf("6. Kilde nummer 6", "7. Kilde nummer 7"), out[2].blocks.take(2).map { it.text })
        assertTrue(out[2].blocks.all { it.type == BlockType.PARAGRAPH })
        // Vanlige fotnoter nederst på en tekstside røres ikke.
        assertEquals(BlockType.FOOTNOTE, out[0].blocks[1].type)
    }

    @Test
    fun `henvisning med flere tall slås opp i kildelisten`() {
        for (b in listOf(book, Sources.normalize(book))) {
            assertEquals(listOf("3" to "Kilde nummer 3", "4" to "Kilde nummer 4"), Sources.lookup(b, 0, "3,4"))
            assertEquals(listOf("6", "7", "8"), Sources.lookup(b, 0, "6–8").map { it.first })
            assertEquals(listOf("a" to "Egen fotnote"), Sources.lookup(b, 0, "a"))
            assertTrue(Sources.lookup(b, 0, "99").isEmpty())
        }
    }

    @Test
    fun `henvisning med flere tall blir én note i leseflyten`() {
        val (shown, notes) = ReadingFlow.displayText("potetgull.[^32,33] Og der.[^75–77]", 0)
        assertEquals("potetgull.32,33 Og der.75–77", shown)
        assertEquals(listOf("32,33", "75–77"), notes.map { it.number })
    }
}
