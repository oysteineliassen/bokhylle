package no.oystein.bokhylle.core

import no.oystein.bokhylle.core.TestPages.line
import no.oystein.bokhylle.core.TestPages.page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTest {
    private fun input(id: Long, lines: List<OcrLine>, blocks: List<OcrBlockInfo> = emptyList(), regions: List<ManualRegion> = emptyList()) =
        LayoutInput(id, OcrPage(lines, blocks), PageNumberDetector.detect(lines), regions)

    @Test
    fun `fotnotereferanser merkes`() {
        assertEquals("Det var sant.[^3] Så gikk han.", LayoutBuilder.markFootnoteRefs(line("Det var sant.³ Så gikk han.", 0.1f)))
        assertEquals("ordet.[^12] videre", LayoutBuilder.markFootnoteRefs(line("ordet.12 videre", 0.1f)))
        // Vanlige tall i teksten røres ikke.
        assertEquals("Han var 12 år i 1984.", LayoutBuilder.markFootnoteRefs(line("Han var 12 år i 1984.", 0.1f)))
        // Lite, hevet tall som eget ord.
        val l = OcrLine(
            "Slik var det 4 og mer", 0.1f, 0.1f, 0.9f, 0.13f,
            elements = listOf(
                OcrElement("Slik", 0.1f, 0.1f, 0.2f, 0.13f), OcrElement("var", 0.21f, 0.1f, 0.3f, 0.13f),
                OcrElement("det", 0.31f, 0.1f, 0.4f, 0.13f), OcrElement("4", 0.41f, 0.098f, 0.42f, 0.112f),
                OcrElement("og", 0.43f, 0.1f, 0.5f, 0.13f), OcrElement("mer", 0.51f, 0.1f, 0.6f, 0.13f),
            ),
        )
        assertEquals("Slik var det[^4] og mer", LayoutBuilder.markFootnoteRefs(l))
    }

    @Test
    fun `farget blokk blir faktaboks med tittel`() {
        val lines = page(listOf("Vanlig tekst her.")).toMutableList()
        lines += line("Fakta om bøker", 0.4f, block = 5)
        lines += line("Den eldste boka er gammel.", 0.43f, block = 5)
        val layout = LayoutBuilder.buildPage(input(1, lines.sortedBy { it.top }, listOf(OcrBlockInfo(5, Box(0.1f, 0.4f, 0.9f, 0.45f), colored = true))))
        val box = layout.blocks.single { it.type == BlockType.BOX }
        assertEquals("Fakta om bøker", box.title)
        assertEquals("Den eldste boka er gammel.", box.text)
        assertTrue(layout.blocks.none { it.type == BlockType.PARAGRAPH && "eldste" in it.text })
    }

    @Test
    fun `kuttet blokk fjernes`() {
        val lines = page(listOf("Hel tekst.")) + line("halv tekst fra nabosid", 0.5f, left = 0f, right = 0.05f, block = 7)
        val layout = LayoutBuilder.buildPage(input(1, lines, listOf(OcrBlockInfo(7, Box(0f, 0.5f, 0.05f, 0.52f), cutByEdge = true))))
        assertTrue(layout.plainText().contains("Hel tekst."))
        assertTrue(!layout.plainText().contains("nabosid"))
    }

    @Test
    fun `spredte korte tekstbiter blir figur`() {
        val lines = page(listOf("Tekst over figuren.")).filter { it.top < 0.3f }.toMutableList()
        lines += line("Hjerte", 0.4f, left = 0.2f, right = 0.3f, block = 20)
        lines += line("Lunge", 0.45f, left = 0.6f, right = 0.7f, block = 21)
        lines += line("Lever", 0.52f, left = 0.3f, right = 0.4f, block = 22)
        lines += line("Figur 3. Kroppens organer.", 0.6f, block = 23)
        val layout = LayoutBuilder.buildPage(input(1, lines))
        val fig = assertNotNull(layout.blocks.singleOrNull { it.type == BlockType.FIGURE }, "${layout.blocks}")
        assertTrue(fig.description!!.contains("Hjerte"))
        assertTrue(!layout.blocks.filter { it.type == BlockType.PARAGRAPH }.any { "Lunge" in it.text })
        assertEquals("Figur 3. Kroppens organer.", layout.blocks.single { it.type == BlockType.CAPTION }.text)
    }

    @Test
    fun `nummerering og referansebiter er ikke en figur`() {
        fun l(t: String, top: Float) = OcrLine(t, 0.1f, top, 0.1f + 0.01f * t.length, top + 0.02f)
        // Referanseliste: tallene i margen og løse doi-biter blir korte ML Kit-blokker.
        val refs = listOf(l("1", 0.2f), l("2", 0.3f), l("doi:10.1159/000079130.", 0.32f), l("3", 0.4f), l("4", 0.5f),
            l("(side 194–206)", 0.15f), l("PMCID:PMC7120250.", 0.45f))
        assertTrue(!LayoutBuilder.looksLikeFigure(refs))
        assertTrue(!LayoutBuilder.looksLikeFigure(listOf(l("då.2022.", 0.2f), l("06.009.", 0.25f), l("WHO.", 0.3f))))
        // Etiketter i en illustrasjon (med tall på en akse) er fortsatt en figur.
        assertTrue(LayoutBuilder.looksLikeFigure(listOf(l("Hjerte", 0.2f), l("Lunge", 0.3f), l("0", 0.4f), l("5", 0.45f), l("10", 0.5f))))
    }

    @Test
    fun `fotnote nederst`() {
        val lines = page(listOf("Dette er sant.³ Og mer tekst her.")).filter { it.top < 0.5f } +
            line("3 Kilde: Store norske leksikon.", 0.8f, height = 0.015f)
        val layout = LayoutBuilder.buildPage(input(1, lines))
        val note = layout.blocks.single { it.type == BlockType.FOOTNOTE }
        assertEquals("3", note.number)
        assertEquals("Kilde: Store norske leksikon.", note.text)
        assertTrue(layout.blocks.first().text.contains("[^3]"))
    }

    @Test
    fun `manuelt område blir figur`() {
        val lines = page(listOf("Første linje.", "Etikett A", "Siste linje her."))
        val region = ManualRegion(BlockType.FIGURE, Box(0f, 0.125f, 1f, 0.155f))
        val layout = LayoutBuilder.buildPage(input(1, lines, regions = listOf(region)))
        val fig = layout.blocks.single { it.type == BlockType.FIGURE }
        assertTrue(fig.manual)
        assertEquals("Tekst i figuren: Etikett A", fig.description)
    }

    @Test
    fun `avsnitt over sideskift og orddeling`() {
        val a = PageLayout(listOf(LayoutBlock(BlockType.PARAGRAPH, "Han gikk inn i bok-")))
        val b = PageLayout(listOf(LayoutBlock(BlockType.PARAGRAPH, "hylla og fant den.")))
        val r = LayoutBuilder.linkPages(listOf(1L to a, 2L to b))
        assertEquals("Han gikk inn i bokhylla", r[0].second.blocks[0].text)
        assertEquals("og fant den.", r[1].second.blocks[0].text)
        assertTrue(r[0].second.continuesToNext)
        assertTrue(r[1].second.continuesFromPrevious)
    }

    @Test
    fun `hele bok med topptekst`() {
        val pages = (1..4).map { n -> input(n.toLong(), page(listOf("Innhold på side ${"abcd"[n - 1]} slutter."), header = "Bokhylla", number = "$n")) }
        val layouts = LayoutBuilder.build(pages)
        for ((id, l) in layouts) {
            assertTrue(!l.plainText().contains("Bokhylla"), l.plainText())
            assertEquals(id.toString(), l.pageNumber)
            assertEquals("Bokhylla", l.header)
        }
    }

    @Test
    fun `Claude-svar leses`() {
        val json = """
            {"page_number":"47","running_header":"KAPITTEL TRE","continues_from_previous":true,
             "blocks":[{"type":"heading","text":"Del 2"},{"type":"paragraph","text":"Tekst[^1]."},
               {"type":"box","title":"Visste du?","text":"Noe.","bbox":[0.1,0.5,0.9,0.7]},
               {"type":"figure","description":"Et kart","bbox":[0.1,0.7,0.9,0.95]},
               {"type":"footnote","number":1,"text":"Kilde."},{"type":"ukjent","text":"x"}]}
        """.trimIndent()
        val l = PageLayout.fromJson(Json.parse(json).obj()!!, PageLayout.SOURCE_CLAUDE)
        assertEquals("47", l.pageNumber)
        assertEquals(5, l.blocks.size)
        assertEquals("1", l.blocks[4].number)
        assertEquals(Box(0.1f, 0.7f, 0.9f, 0.95f), l.blocks[3].box)
        assertEquals(l, PageLayout.decode(l.encode()))
        assertEquals("Del 2\n\nTekst.\n\nVisste du?\nNoe.\n\nEt kart\n\n1 Kilde.", l.plainText())
    }

    @Test
    fun `redigering tur-retur beholder figurbildet`() {
        val l = PageLayout(
            listOf(
                LayoutBlock(BlockType.HEADING, "Tittel"),
                LayoutBlock(BlockType.PARAGRAPH, "Første avsnitt[^2]."),
                LayoutBlock(BlockType.BOX, "Innhold.\n\nMer.", title = "Boks"),
                LayoutBlock(BlockType.FIGURE, description = "Kart", image = "/fig.jpg", box = Box(0f, 0f, 1f, 0.5f)),
                LayoutBlock(BlockType.CAPTION, "Kart over byen"),
                LayoutBlock(BlockType.FOOTNOTE, "Noten.", number = "2"),
            ),
            pageNumber = "5",
        )
        val markup = LayoutMarkup.toMarkup(l)
        assertEquals(l, LayoutMarkup.parse(markup, l))
        val edited = LayoutMarkup.parse(markup.replace("Første", "Andre").replace("[figur: Kart]", "[figur: Bykart]"), l)
        assertEquals("Andre avsnitt[^2].", edited.blocks[1].text)
        assertEquals("/fig.jpg", edited.blocks[3].image)
        assertEquals("Bykart", edited.blocks[3].description)
    }

    @Test
    fun `manuelt område i Claude-oppsett`() {
        val l = PageLayout(
            listOf(
                LayoutBlock(BlockType.PARAGRAPH, "Over.", box = Box(0.1f, 0.1f, 0.9f, 0.2f)),
                LayoutBlock(BlockType.HEADING, "Visste du?", box = Box(0.1f, 0.3f, 0.9f, 0.33f)),
                LayoutBlock(BlockType.PARAGRAPH, "Fakta her.", box = Box(0.1f, 0.34f, 0.9f, 0.4f)),
                LayoutBlock(BlockType.PARAGRAPH, "Under.", box = Box(0.1f, 0.6f, 0.9f, 0.7f)),
            ),
            source = PageLayout.SOURCE_CLAUDE,
        )
        val r = LayoutOps.applyRegions(l, listOf(ManualRegion(BlockType.BOX, Box(0.05f, 0.28f, 0.95f, 0.45f))))
        assertEquals(listOf(BlockType.PARAGRAPH, BlockType.BOX, BlockType.PARAGRAPH), r.blocks.map { it.type })
        assertEquals("Visste du?", r.blocks[1].title)
        assertEquals("Fakta her.", r.blocks[1].text)
        assertNull(LayoutOps.applyRegions(l, emptyList()).blocks.firstOrNull { it.type == BlockType.BOX })
    }
}
