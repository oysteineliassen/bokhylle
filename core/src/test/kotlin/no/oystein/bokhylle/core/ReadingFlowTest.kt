package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingFlowTest {
    private fun p(vararg blocks: LayoutBlock) = PageLayout(blocks.toList())
    private fun para(t: String) = LayoutBlock(BlockType.PARAGRAPH, t)
    private fun head(t: String) = LayoutBlock(BlockType.HEADING, t)

    @Test
    fun `avsnitt som fortsetter over sideskiftet blir ett`() {
        val flow = ReadingFlow.build(listOf(
            p(para("Han åpnet sitt første solsanatorium i Leysin i")),
            p(para("1903. Han kombinerte sollys og fjelluft."), head("SLAG, SOL OG SKALLE"), para("Herodot skrev.")),
        ))
        assertEquals("Han åpnet sitt første solsanatorium i Leysin i 1903. Han kombinerte sollys og fjelluft.", flow[0].text)
        assertEquals(listOf(0, 1), flow[0].pages)
        // Underoverskrift midt på siden: ingen ny skjermside.
        assertFalse(flow[1].breakBefore)
        // Ankre over sideskiftet: «1903» står først på side 2.
        val pos = flow[0].positionOf(ReadingFlow.Anchor(1, 0, 0))!!
        assertEquals("1903", flow[0].text.substring(pos, pos + 4))
        assertEquals(ReadingFlow.Anchor(1, 0, 5), flow[0].anchorAt(pos + 5))
        assertEquals(listOf(0, 1), flow[0].pagesIn(0, flow[0].text.length))
    }

    @Test
    fun `hovedkapittel øverst på en side begynner på ny skjermside`() {
        val flow = ReadingFlow.build(listOf(
            p(para("Slutten på kapittelet.")),
            p(head("KAPITTEL 2"), para("Ny tekst.")),
        ))
        assertTrue(flow[1].breakBefore)
        assertFalse(flow[2].breakBefore)
    }

    @Test
    fun `avsluttet setning og stor forbokstav fortsetter ikke`() {
        val flow = ReadingFlow.build(listOf(p(para("Det var slutt.")), p(para("Neste avsnitt."))))
        assertEquals(2, flow.size)
        assertFalse(flow[1].breakBefore)
    }

    @Test
    fun `fotnotemerker blir tall med posisjon`() {
        val (text, notes) = ReadingFlow.displayText("Sant.[^3] Mer.", 0)
        assertEquals("Sant.3 Mer.", text)
        assertEquals(5, notes.single().start)
    }

    @Test
    fun `setningen rundt et punkt, og utvidelse`() {
        val t = "Første setning. Andre «setning»! Tredje her"
        val r = ReadingFlow.sentenceAt(t, 20)
        assertEquals("Andre «setning»!", t.substring(r))
        assertEquals("Andre «setning»! Tredje her", t.substring(ReadingFlow.extendToNextSentence(t, r)))
        assertEquals("Første setning.", t.substring(ReadingFlow.sentenceAt(t, 0)))
    }

    @Test
    fun `manglende sider gir egen skjermside`() {
        val flow = ReadingFlow.build(listOf(p(para("Slutt.")), p(para("Videre."))), missingAfter = { if (it == 0) listOf("12–14" to 3) else emptyList() })
        assertEquals("12–14", flow[1].missingLabel)
        assertTrue(flow[1].breakBefore)
        assertTrue(flow[2].breakBefore)
    }
}
