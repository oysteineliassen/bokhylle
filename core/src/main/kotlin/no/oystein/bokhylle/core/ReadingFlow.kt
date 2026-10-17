package no.oystein.bokhylle.core

/**
 * Teksten slik leseren viser den: boksidene etter hverandre som én strøm av blokker. Et avsnitt som fortsetter
 * over et sideskift blir ett avsnitt, og bare hovedkapitler (en overskrift øverst på en bokside etter en avsluttet
 * side) begynner på ny skjermside.
 *
 * Posisjoner i teksten («ankre») er (bokside, blokk, tegn) i blokkens visningstekst, der fotnotemerker «[^3]» er
 * erstattet av tallet. De brukes til lesepunkt, bokmerke og markeringer, og overlever omformatering av leseren.
 */
object ReadingFlow {
    /** Et punkt i boka: blokk nr. [block] på boksiden med indeks [page], [offset] tegn inn i visningsteksten. */
    data class Anchor(val page: Int, val block: Int, val offset: Int)

    /** En bit av en flytblokk som kommer fra én blokk på én bokside. [start] er posisjonen i flytblokkens tekst. */
    data class Span(val start: Int, val length: Int, val page: Int, val block: Int) {
        val end get() = start + length
    }

    /** En fotnotereferanse i teksten: posisjonen til tallet, og boksiden den står på. */
    data class Note(val start: Int, val end: Int, val number: String, val page: Int)

    data class Block(
        val type: BlockType,
        val text: String,
        val title: String? = null,
        val spans: List<Span>,
        val notes: List<Note> = emptyList(),
        /** Begynner på ny skjermside (hovedkapittel eller merknad om manglende sider). */
        val breakBefore: Boolean = false,
        /** Teksten er fra en innholdsfortegnelse (oppføringene kan trykkes på). */
        val toc: Boolean = false,
        val image: String? = null,
        val description: String? = null,
        val box: Box? = null,
        /** «Side 65–79 er ikke lagt inn ennå»: [missingLabel] og antallet. */
        val missingLabel: String? = null,
        val missingCount: Int = 0,
    ) {
        /** Boksiden blokken begynner på. */
        val page get() = spans.first().page
        val pages get() = spans.map { it.page }.distinct()

        /** Posisjonen i [text] for et ankre, eller null hvis ankeret ikke er i denne blokken. */
        fun positionOf(a: Anchor): Int? = spans.firstOrNull { it.page == a.page && it.block == a.block }
            ?.let { it.start + a.offset.coerceIn(0, it.length) }

        /** Ankeret for en posisjon i [text]. */
        fun anchorAt(pos: Int): Anchor {
            val s = spans.lastOrNull { it.start <= pos } ?: spans.first()
            return Anchor(s.page, s.block, (pos - s.start).coerceIn(0, s.length))
        }

        /** Boksidene posisjonene [from] til [to] (eksklusiv) i teksten spenner over. */
        fun pagesIn(from: Int, to: Int): List<Int> =
            spans.filter { it.start < to && it.end > from }.map { it.page }.distinct().ifEmpty { listOf(anchorAt(from).page) }
    }

    /** Visningsteksten: fotnotemerker «[^3]» blir «3» (og hvor de står). */
    fun displayText(raw: String, page: Int): Pair<String, List<Note>> {
        val sb = StringBuilder()
        val notes = mutableListOf<Note>()
        var last = 0
        for (m in PageLayout.FOOTNOTE_REF.findAll(raw)) {
            sb.append(raw, last, m.range.first)
            val n = m.groupValues[1]
            notes += Note(sb.length, sb.length + n.length, n, page)
            sb.append(n)
            last = m.range.last + 1
        }
        sb.append(raw.substring(last))
        return sb.toString() to notes
    }

    /** Blokkene leseren viser for én bokside: uten fotnoter, og med beskrivelsen under figurer uten bildetekst. */
    fun visibleBlocks(layout: PageLayout): List<Pair<Int, LayoutBlock>> {
        val list = layout.blocks.withIndex().filter { it.value.type != BlockType.FOOTNOTE }
        return list.flatMapIndexed { i, (index, b) ->
            val described = b.type == BlockType.FIGURE && b.image != null && !b.description.isNullOrBlank() &&
                list.getOrNull(i + 1)?.value?.type != BlockType.CAPTION
            // Beskrivelsen får en egen blokkindeks bak sidens blokker, så ankre i den ikke kolliderer.
            if (described) listOf(index to b, (DESCRIPTION_BASE + index) to b.copy(type = BlockType.CAPTION, text = b.description!!, image = null))
            else listOf(index to b)
        }
    }

    private const val DESCRIPTION_BASE = 10_000

    private fun endsOpen(text: String): Boolean {
        val t = text.trimEnd()
        return t.isNotEmpty() && t.last() !in ".!?:»”\"…)"
    }

    /**
     * Bygger strømmen. [missingAfter] gir merknader om manglende sider etter boksiden med en gitt indeks
     * (etikett og antall).
     */
    fun build(pages: List<PageLayout>, missingAfter: (Int) -> List<Pair<String, Int>> = { emptyList() }): List<Block> {
        val out = mutableListOf<Block>()
        var forceBreak = false
        for ((pi, layout) in pages.withIndex()) {
            val toc = TocLinks.isTocPage(layout)
            val blocks = visibleBlocks(layout).filter { (_, b) -> b.type == BlockType.FIGURE || b.text.isNotBlank() || b.title != null }
            val prevPara = out.lastOrNull()?.takeIf { it.type == BlockType.PARAGRAPH && it.spans.last().page == pi - 1 }
            val prevLayout = pages.getOrNull(pi - 1)
            blocks.forEachIndexed { k, (bi, b) ->
                val (text, notes) = displayText(b.text, pi)
                val first = k == 0
                // Avsnittet fortsetter fra forrige bokside: legges til det forrige.
                val continues = first && b.type == BlockType.PARAGRAPH && prevPara != null && !forceBreak &&
                    (layout.continuesFromPrevious || prevLayout?.continuesToNext == true || endsOpen(prevPara.text) ||
                        text.firstOrNull()?.isLowerCase() == true)
                if (continues) {
                    val joiner = if (prevPara!!.text.endsWith("-") || prevPara.text.endsWith(" ")) "" else " "
                    val start = prevPara.text.length + joiner.length
                    out[out.lastIndex] = prevPara.copy(
                        text = prevPara.text + joiner + text,
                        spans = prevPara.spans + Span(start, text.length, pi, bi),
                        notes = prevPara.notes + notes.map { it.copy(start = it.start + start, end = it.end + start) },
                    )
                    return@forEachIndexed
                }
                // Hovedkapittel: overskrift først på boksiden, og forrige bokside sluttet med en hel setning.
                val chapter = first && b.type == BlockType.HEADING && out.isNotEmpty() &&
                    out.last().type != BlockType.HEADING && !(out.last().type == BlockType.PARAGRAPH && endsOpen(out.last().text))
                out += Block(
                    type = b.type, text = text, title = b.title, spans = listOf(Span(0, text.length, pi, bi)), notes = notes,
                    breakBefore = forceBreak || chapter, toc = toc, image = b.image, description = b.description, box = b.box,
                )
                forceBreak = false
            }
            for ((label, count) in missingAfter(pi)) {
                out += Block(BlockType.PARAGRAPH, "", spans = listOf(Span(0, 0, pi, -1)), breakBefore = true,
                    missingLabel = label, missingCount = count)
                forceBreak = true
            }
        }
        return out
    }

    private val sentenceEnd = Regex("""[.!?…]+[»”"')]*(?=\s|$)""")

    /** Setningen rundt posisjonen [pos] i [text] (uten mellomrom i endene). */
    fun sentenceAt(text: String, pos: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val p = pos.coerceIn(0, text.length - 1)
        var start = 0
        var end = text.length
        for (m in sentenceEnd.findAll(text)) {
            val e = m.range.last + 1
            if (e <= p) start = e else { end = e; break }
        }
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return start until end
    }

    /** Utvider et utvalg med neste setning (eller til slutten av teksten). */
    fun extendToNextSentence(text: String, range: IntRange): IntRange {
        if (range.isEmpty() || range.last + 1 >= text.length) return range
        var next = range.last + 1
        while (next < text.length && text[next].isWhitespace()) next++
        if (next >= text.length) return range
        return range.first..sentenceAt(text, next).last
    }
}
