package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FigureKindTest {
    private val box = Box(0.1f, 0.1f, 0.5f, 0.5f)
    private fun fig(description: String?, kind: FigureKind? = null, redraw: Boolean? = null, image: String = "figur.webp") =
        LayoutBlock(BlockType.FIGURE, description = description, kind = kind, redraw = redraw, box = box, image = image)

    @Test
    fun `logoer og merker kjennes igjen på beskrivelsen`() {
        listOf(
            "FSC-merke med tre-symbol og teksten «MIX, Papir fra bærekraftige kilder, FSC C107574»",
            "PEFC-sertifisering",
            "Svanemerket med teksten 3041 0123",
            "Forlagets logo",
            "Strekkode med ISBN 978-82-02-12345-6",
            "QR-kode som lenker til nettsiden",
            "Miljømerke for trykksaker",
        ).forEach { assertEquals(FigureKind.LOGO, FigureKind.guess(it), it) }
    }

    @Test
    fun `vanlige figurer gjettes ikke som logo`() {
        listOf(
            "Søylediagram over UV-indeks i Oslo, Trondheim og Tromsø",
            "Tegning av et vannmolekyl med positiv og negativ side",
            "Kart over Norge med soner for D-vitaminproduksjon",
            "Teknologisk utvikling vist som en kurve",
            null,
        ).forEach { assertNull(FigureKind.guess(it), it) }
    }

    @Test
    fun `analysens type går foran gjetningen`() {
        // Claude sier diagram selv om beskrivelsen nevner et merke.
        assertEquals(FigureKind.DIAGRAM, FigureKind.effective(fig("Diagram over merkevarer", FigureKind.DIAGRAM)))
        assertEquals(FigureKind.LOGO, FigureKind.effective(fig("FSC-merke")))
        assertNull(FigureKind.effective(fig("Graf over solinnstråling")))
    }

    @Test
    fun `bare diagram, kart, graf og tegning tegnes på nytt`() {
        val redraw = FigureKind.entries.filter { FigureKind.shouldRedraw(fig("x", it)) }.toSet()
        assertEquals(setOf(FigureKind.DIAGRAM, FigureKind.KART, FigureKind.GRAF, FigureKind.TEGNING), redraw)
        // Ukjent type (eldre analyse, ML Kit) tas med som før.
        assertTrue(FigureKind.shouldRedraw(fig(null)))
        assertTrue(FigureKind.shouldRedraw(fig("Graf over solinnstråling")))
        // Eldre FSC-merke uten type utelates.
        assertFalse(FigureKind.shouldRedraw(fig("FSC-merke med tre-symbol")))
    }

    @Test
    fun `brukerens valg overstyrer typen`() {
        assertTrue(FigureKind.shouldRedraw(fig("FSC-merke", FigureKind.LOGO, redraw = true)))
        assertFalse(FigureKind.shouldRedraw(fig("Søylediagram", FigureKind.GRAF, redraw = false)))
    }

    @Test
    fun `eksportfilteret på en kolofonside`() {
        val blocks = listOf(
            LayoutBlock(BlockType.PARAGRAPH, "© Forfatteren 2025"),
            fig("FSC-merke med tre-symbol og teksten «MIX, Papir fra bærekraftige kilder, FSC C107574»", FigureKind.LOGO),
            fig("Strekkode", image = "strekkode.webp"),
            fig("Graf over UV-indeks", FigureKind.GRAF, image = "graf.webp"),
            fig("Fotografi av forfatteren", FigureKind.FOTO, image = "foto.webp"),
            fig("Kart", FigureKind.KART, image = "tegning-1.webp"), // allerede tegnet
            fig("Tegning uten bilde", FigureKind.TEGNING).copy(image = null),
        )
        assertEquals(listOf(3), FigureKind.forExport(blocks) { it.startsWith("tegning-") })
        // Bare logoer og foto: tom eksport.
        assertTrue(FigureKind.forExport(blocks.take(3) + blocks[4]) { false }.isEmpty())
    }

    @Test
    fun `kind og redraw lagres i oppsettet, og mangler i eldre oppsett`() {
        val layout = PageLayout(listOf(fig("FSC-merke", FigureKind.LOGO, redraw = true), fig("Graf")))
        val back = PageLayout.decode(layout.encode())!!
        assertEquals(FigureKind.LOGO, back.blocks[0].kind)
        assertEquals(true, back.blocks[0].redraw)
        assertNull(back.blocks[1].kind)
        assertNull(back.blocks[1].redraw)
        // Claudes svar med kind, og en ukjent verdi.
        val claude = PageLayout.fromJson(
            mapOf("blocks" to listOf(
                mapOf("type" to "figure", "description" to "Kart over Europa", "kind" to "kart", "bbox" to listOf(0.1, 0.1, 0.9, 0.5)),
                mapOf("type" to "figure", "description" to "?", "kind" to "noe annet", "bbox" to listOf(0.1, 0.6, 0.9, 0.9)),
            )),
            PageLayout.SOURCE_CLAUDE,
        )
        assertEquals(FigureKind.KART, claude.blocks[0].kind)
        assertNull(claude.blocks[1].kind)
    }
}
