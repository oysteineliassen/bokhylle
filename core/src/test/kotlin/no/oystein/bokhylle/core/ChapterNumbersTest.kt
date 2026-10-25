package no.oystein.bokhylle.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterNumbersTest {
    // Side som «Hva og når skal vi spise?» s. 58: løpende topptekst, stort grått «38», tittel, brødtekst, sidetall.
    private val lines = listOf(
        OcrLine("HVA OG NÅR SKAL VI SPISE?", 0.10f, 0.050f, 0.45f, 0.062f),
        OcrLine("38", 0.10f, 0.090f, 0.17f, 0.140f),
        OcrLine("Spis mest når det er lyst", 0.10f, 0.150f, 0.55f, 0.175f),
    ) + (0 until 20).map { i ->
        OcrLine("Kroppen er programmert til å gjøre ulike ting $i", 0.10f, 0.20f + i * 0.03f, 0.90f, 0.218f + i * 0.03f)
    } + OcrLine("58", 0.08f, 0.95f, 0.11f, 0.965f)

    private fun layout(heading: String) = PageLayout(
        listOf(
            LayoutBlock(BlockType.HEADING, heading, box = Box(0.10f, 0.148f, 0.55f, 0.178f)),
            LayoutBlock(BlockType.PARAGRAPH, "Kroppen er programmert …", box = Box(0.10f, 0.20f, 0.90f, 0.80f)),
        ),
        pageNumber = "58",
    )

    @Test
    fun `stort kapittelnummer over tittelen settes inn i overskriften`() {
        assertEquals("38 Spis mest når det er lyst", ChapterNumbers.restore(layout("Spis mest når det er lyst"), lines).blocks[0].text)
    }

    @Test
    fun `nummer som allerede er med legges ikke til igjen`() {
        assertEquals("38 Spis mest når det er lyst", ChapterNumbers.restore(layout("38 Spis mest når det er lyst"), lines).blocks[0].text)
        val separate = layout("Spis mest når det er lyst").let { it.copy(blocks = listOf(LayoutBlock(BlockType.HEADING, "38")) + it.blocks) }
        assertEquals("Spis mest når det er lyst", ChapterNumbers.restore(separate, lines).blocks[1].text)
    }

    @Test
    fun `sidetall og tall i vanlig skrift blir ikke kapittelnummer`() {
        val small = lines.map { if (it.text == "38") it.copy(bottom = it.top + 0.018f) else it }
        assertEquals("Spis mest når det er lyst", ChapterNumbers.restore(layout("Spis mest når det er lyst"), small).blocks[0].text)
        // Overskrift langt nede på siden: tallet øverst hører ikke til den.
        val far = layout("Spis mest").let { l -> l.copy(blocks = listOf(l.blocks[0].copy(box = Box(0.10f, 0.50f, 0.55f, 0.53f)))) }
        assertEquals("Spis mest", ChapterNumbers.restore(far, lines).blocks[0].text)
    }

    @Test
    fun `overskrift med bare nummeret får tittelen fra linjen under`() {
        val only = PageLayout(listOf(
            LayoutBlock(BlockType.HEADING, "38", box = Box(0.10f, 0.088f, 0.17f, 0.142f)),
            LayoutBlock(BlockType.PARAGRAPH, "Kroppen er programmert …", box = Box(0.10f, 0.20f, 0.90f, 0.80f)),
        ))
        assertEquals("38 Spis mest når det er lyst", ChapterNumbers.restore(only, lines).blocks[0].text)
    }

    @Test
    fun `tittel i title-feltet legges til overskriften`() {
        val l = PageLayout.fromJson(
            mapOf("blocks" to listOf(
                mapOf("type" to "heading", "text" to "35", "title" to "Hyppige måltider gir et ustabilt blodsukker"),
                mapOf("type" to "heading", "text" to "Kapittel", "title" to "Kapittel"),
            )),
            PageLayout.SOURCE_CLAUDE,
        )
        assertEquals("35 Hyppige måltider gir et ustabilt blodsukker", l.blocks[0].text)
        assertEquals("Kapittel", l.blocks[1].text)
    }

    @Test
    fun `feillest kapittelnummer rettes eller fylles inn fra nummereringen`() {
        fun page(mark: String, title: String) = layout(title) to lines.map { if (it.text == "38") it.copy(text = mark) else it }
        // «Z6» tolkes som 26.
        assertEquals("26 Spis mest", ChapterNumbers.restore(page("Z6", "Spis mest").first, page("Z6", "Spis mest").second).blocks[0].text)
        // «2б» kan ikke leses, men står mellom kapittel 25 og 27.
        val book = ChapterNumbers.restoreBook(listOf(page("25", "A"), page("2б", "B"), page("27", "C")))
        assertEquals(listOf("25 A", "26 B", "27 C"), book.map { it.blocks[0].text })
        // Passer ikke hullet (25 → 28 med én ukjent), gjettes ingenting.
        val gap = ChapterNumbers.restoreBook(listOf(page("25", "A"), page("2б", "B"), page("28", "C")))
        assertEquals("B", gap[1].blocks[0].text)
    }
}
