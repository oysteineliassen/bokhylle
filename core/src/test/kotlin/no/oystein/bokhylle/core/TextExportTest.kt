package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertTrue

class TextExportTest {
    @Test
    fun `tekst med overskrifter, avsnitt over sideskift og fotnoter`() {
        val pages = listOf(
            PageLayout(listOf(
                LayoutBlock(BlockType.HEADING, "Lysfysikk"),
                LayoutBlock(BlockType.PARAGRAPH, "Sollys består av fotoner[^1] med ulike"),
                LayoutBlock(BlockType.FOOTNOTE, "Se kapittel 3.", number = "1"),
            )),
            PageLayout(listOf(
                LayoutBlock(BlockType.PARAGRAPH, "bølgelengder."),
                LayoutBlock(BlockType.BOX, "Viktig å vite.", title = "Fakta"),
            )),
        )
        val txt = TextExport.render("Solkuren", "Forfatter", pages)
        assertTrue(txt.startsWith("Solkuren\nForfatter\n"), txt)
        assertTrue("Lysfysikk\n---" in txt, txt)
        assertTrue("Sollys består av fotoner[1] med ulike bølgelengder." in txt, txt)
        assertTrue("| Fakta\n|\n| Viktig å vite." in txt, txt)
        assertTrue("Noter\n-----\n[1] Se kapittel 3." in txt, txt)
    }
}
