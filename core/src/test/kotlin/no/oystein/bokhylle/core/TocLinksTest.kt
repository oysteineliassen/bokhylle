package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TocLinksTest {
    private fun page(vararg texts: String) = PageLayout(texts.map { LayoutBlock(BlockType.PARAGRAPH, it) })

    @Test
    fun `oppføringer med prikker og tall`() {
        val text = "Forord 7\nKapittel 1: Lys ........ 13\nDel 2 – Solen og huden    47\nRegister 401"
        val e = TocLinks.entries(text)
        assertEquals(listOf(7, 13, 47, 401), e.map { it.page })
        assertEquals("Kapittel 1: Lys ........ 13", text.substring(e[1].range))
        assertEquals("13", text.substring(e[1].numberStart, e[1].range.last + 1))
    }

    @Test
    fun `innholdsfortegnelse kjennes igjen, vanlig tekst ikke`() {
        assertTrue(TocLinks.isTocPage(page("INNHOLD", "Forord 7", "Lys og liv 13", "Solen 29", "Huden 47", "Register 401")))
        assertFalse(TocLinks.isTocPage(page("Han gikk hjem klokken 5", "Det var en lang dag, og alt tok tid.",
            "Hun ventet til 8", "Så kom regnet.", "Ingen visste hvorfor det skjedde.", "Det var i 1985")))
    }

    @Test
    fun `sidetallet finnes, eller nærmeste før`() {
        val numbers = listOf(null, "5", "(6)", "7", "9", "10")
        assertEquals(2, TocLinks.targetIndex(6, numbers))
        assertEquals(3, TocLinks.targetIndex(8, numbers))
        assertNull(TocLinks.targetIndex(3, numbers))
    }
}
