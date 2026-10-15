package no.oystein.bokhylle.core

import no.oystein.bokhylle.core.TestPages.page
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageComplexityTest {
    private fun layoutOf(vararg blocks: LayoutBlock) = PageLayout(blocks.toList())
    private val paragraph = LayoutBlock(BlockType.PARAGRAPH, text = "Vanlig tekst.")

    @Test
    fun `ren tekstside klarer seg med den billige modellen`() {
        val lines = page(List(20) { "linje $it med tekst" }, number = "12")
        assertFalse(PageComplexity.needsStrongModel(layoutOf(paragraph), lines, hasManualRegions = false))
    }

    @Test
    fun `figur, faktaboks, fotnote og markerte områder trenger den sterke`() {
        val lines = page(List(20) { "linje $it" })
        assertTrue(PageComplexity.needsStrongModel(layoutOf(paragraph, LayoutBlock(BlockType.FIGURE)), lines, false))
        assertTrue(PageComplexity.needsStrongModel(layoutOf(paragraph, LayoutBlock(BlockType.BOX, text = "Fakta")), lines, false))
        assertTrue(PageComplexity.needsStrongModel(layoutOf(LayoutBlock(BlockType.PARAGRAPH, text = "Se note.[^3]")), lines, false))
        assertTrue(PageComplexity.needsStrongModel(layoutOf(paragraph), lines, hasManualRegions = true))
    }

    @Test
    fun `stort tomrom mellom tekstlinjene tyder på et bilde`() {
        val lines = listOf(
            TestPages.line("Overskrift over bildet", 0.08f),
            TestPages.line("tekst", 0.12f),
            TestPages.line("Bildetekst under bildet", 0.62f),
            TestPages.line("mer tekst", 0.66f),
            TestPages.line("enda mer tekst", 0.70f),
        )
        assertTrue(PageComplexity.needsStrongModel(layoutOf(paragraph), lines, false))
    }

    @Test
    fun `kapittelslutt med sidetallet langt nede er en tekstside`() {
        val lines = List(8) { TestPages.line("tekst $it", 0.1f + it * 0.03f) } + TestPages.line("47", 0.92f, left = 0.48f, right = 0.52f)
        assertFalse(PageComplexity.needsStrongModel(layoutOf(paragraph), lines, false))
    }
}
