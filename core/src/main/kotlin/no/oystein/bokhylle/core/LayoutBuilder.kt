package no.oystein.bokhylle.core

import kotlin.math.abs

/** Et område brukeren har markert på sidebildet som figur eller faktaboks. */
data class ManualRegion(val type: BlockType, val box: Box) {
    companion object {
        fun encode(regions: List<ManualRegion>): String =
            Json.write(regions.map { mapOf("type" to it.type.json, "bbox" to it.box.toList()) })

        fun decode(text: String?): List<ManualRegion> {
            val list = runCatching { Json.parse(text ?: return emptyList()) }.getOrNull()
            return list.arr().mapNotNull { it.obj() }.mapNotNull { o ->
                val type = BlockType.of(o.str("type")) ?: return@mapNotNull null
                ManualRegion(type, Box.of(o["bbox"]) ?: return@mapNotNull null)
            }
        }
    }
}

/** Det byggeren trenger for én side. */
data class LayoutInput(
    val id: Long,
    val ocr: OcrPage,
    val pageNumber: DetectedPageNumber?,
    val regions: List<ManualRegion> = emptyList(),
)

/**
 * Bygger sideoppsett (blokker) fra ML Kit-linjer, for sider som ikke er analysert av Claude:
 * fjerner sidetall, løpende topptekst og blokker som er kuttet av bildekanten, finner faktabokser (farget bakgrunn),
 * figurer (spredte korte tekstbiter), bildetekster og fotnoter, og bygger avsnitt fra resten.
 */
object LayoutBuilder {
    private val superscripts = mapOf('⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9')
    private val gluedRef = Regex("^(.*[\\p{L}»”\"’)][.,;:!?»”\"’]?)(\\d{1,2})([.,;:!?]?)$")
    private val captionStart = Regex("^(fig(ur)?\\.?|bilde|foto|illustrasjon|kart|tabell)\\s*\\d*", RegexOption.IGNORE_CASE)

    fun build(pages: List<LayoutInput>): Map<Long, PageLayout> {
        val headers = TextCleaner.findRunningHeaders(pages.map { CleanInput(it.id, it.ocr.lines, it.pageNumber) })
        val layouts = pages.map { page ->
            val removed = page.ocr.lines.indices.filter { i ->
                (page.pageNumber?.lineIndex == i) || (page.id to i) in headers
            }.toSet()
            val headerText = removed.filter { it != page.pageNumber?.lineIndex || page.pageNumber?.wholeLine == false }
                .map { page.ocr.lines[it].text.replace(page.pageNumber?.token ?: "\u0000", "").trim() }
                .filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { null }
            page.id to buildPage(page, removed).copy(pageNumber = page.pageNumber?.number?.label, header = headerText)
        }
        return linkPages(layouts).toMap()
    }

    /** Ett sideoppsett. [removedLines] er sidetall og topptekst som allerede er funnet. */
    fun buildPage(page: LayoutInput, removedLines: Set<Int> = emptySet()): PageLayout {
        val ocr = page.ocr
        val cutBlocks = ocr.blocks.filter { it.cutByEdge }.map { it.index }.toSet()
        val coloredBlocks = ocr.blocks.filter { it.colored }.map { it.index }.toSet()
        val medianHeight = ocr.lines.map { it.height }.median()

        // Linjene som er igjen, med fotnotereferanser merket.
        data class L(val index: Int, val line: OcrLine)
        var lines = ocr.lines.withIndex()
            .filter { (i, l) -> i !in removedLines && l.block !in cutBlocks && l.text.isNotBlank() }
            .map { (i, l) -> L(i, l.copy(text = markFootnoteRefs(l))) }

        val blocks = mutableListOf<LayoutBlock>()

        // 1. Manuelle områder vinner over alt annet.
        for (region in page.regions) {
            val inside = lines.filter { region.box.contains(it.line.left + it.line.width / 2, it.line.centerY) }
            lines = lines - inside.toSet()
            blocks += regionBlock(region.type, region.box, inside.map { it.line }, manual = true)
        }

        // 2. Fotnoter: små linjer nederst som begynner med et tall.
        val bodyBottom = lines.maxOfOrNull { it.line.bottom } ?: 0f
        val footnoteLines = mutableListOf<L>()
        run {
            val candidates = lines.sortedBy { it.line.top }.reversed()
            for (l in candidates) {
                if (l.line.bottom < bodyBottom - 0.25f) break
                val small = medianHeight > 0f && l.line.height < medianHeight * 0.88f
                if (!small) break
                footnoteLines.add(0, l)
            }
        }
        val footnotes = footnoteBlocks(footnoteLines.map { it.line })
        if (footnotes.isNotEmpty()) lines = lines - footnoteLines.toSet() else footnoteLines.clear()

        // 3. Faktabokser: blokker med farget bakgrunn (nabo-blokker slås sammen).
        val boxLines = lines.filter { it.line.block in coloredBlocks }
        if (boxLines.isNotEmpty()) {
            lines = lines - boxLines.toSet()
            for (group in groupByProximity(boxLines.map { it.line })) {
                val area = group.map { Box(it.left, it.top, it.right, it.bottom) }.reduce(Box::union)
                blocks += regionBlock(BlockType.BOX, area, group, manual = false)
            }
        }

        // 4. Figurer: flere smale blokker med korte tekstbiter (etiketter i en illustrasjon).
        val byBlock = lines.groupBy { it.line.block }
        val labelBlocks = byBlock.filter { (_, ls) ->
            val words = ls.sumOf { it.line.text.split(' ').size }.toFloat() / ls.size
            val box = ls.map { Box(it.line.left, it.line.top, it.line.right, it.line.bottom) }.reduce(Box::union)
            words <= 3f && box.width < 0.4f && ls.none { it.line.text.trim().endsWith('.') && it.line.text.length > 25 }
        }
        val labelLines = labelBlocks.values.flatten()
        val clusters = groupByProximity(labelLines.map { it.line }, gap = 0.12f).filter { cluster ->
            cluster.map { it.block }.distinct().size >= 2 && looksLikeFigure(cluster)
        }
        for (cluster in clusters) {
            val area = cluster.map { Box(it.left, it.top, it.right, it.bottom) }.reduce(Box::union).pad(0.03f)
            lines = lines.filter { l -> cluster.none { it === l.line } }
            blocks += regionBlock(BlockType.FIGURE, area, cluster, manual = false)
        }

        // 5. Resten er brødtekst: overskrifter, avsnitt og bildetekster.
        val body = TextCleaner.paragraphBlocks(lines.sortedWith(compareBy({ it.index })).map { it.line })
        for (b in body) {
            val figureAbove = blocks.any { it.type == BlockType.FIGURE && it.box != null && b.box != null && abs(b.box.top - it.box.bottom) < 0.06f }
            blocks += if (b.type == BlockType.PARAGRAPH && captionStart.containsMatchIn(b.text) && (figureAbove || b.text.length < 200)) {
                b.copy(type = BlockType.CAPTION)
            } else b
        }

        // Leserekkefølge: ovenfra og ned (stabil for blokker uten plassering), fotnoter sist.
        val ordered = blocks.withIndex().sortedWith(compareBy({ it.value.box?.top ?: Float.MAX_VALUE }, { it.index })).map { it.value } + footnotes
        return PageLayout(ordered, source = PageLayout.SOURCE_MLKIT)
    }

    private val referenceToken = Regex("(?i)\\b(doi|pmid|pmcid|https?|www)\\b")
    private val word = Regex("\\p{L}{3,}")
    private val listNumber = Regex("^\\d{1,3}\\.?$")

    /**
     * Korte tekstbiter i en klynge er bare en figur når de ligner etiketter. Nummereringen i en referanseliste
     * (1, 2, 3 … nedover i margen) og løse biter av referanser (doi, PMID, årstall) gir også korte ML Kit-blokker,
     * men er ikke en figur: krever minst to etiketter med ord, og ingen fortløpende nummerering.
     */
    internal fun looksLikeFigure(cluster: List<OcrLine>): Boolean {
        val texts = cluster.sortedBy { it.top }.map { it.text.trim() }
        val wordy = texts.count { t -> !referenceToken.containsMatchIn(t) && word.containsMatchIn(t) }
        if (wordy < 2) return false
        val numbers = texts.filter { listNumber.matches(it) }.map { it.trimEnd('.').toInt() }
        val consecutive = numbers.zipWithNext().count { (a, b) -> b == a + 1 }
        return consecutive < 2
    }

    /** Lager en figur eller faktaboks av linjene i et område. */
    fun regionBlock(type: BlockType, box: Box, lines: List<OcrLine>, manual: Boolean): LayoutBlock {
        val sorted = lines.sortedBy { it.top }
        return when (type) {
            BlockType.BOX -> {
                val first = sorted.firstOrNull()
                val median = sorted.map { it.height }.median()
                // Tittel: første linje hvis den er kort uten punktum, eller i større skrift.
                val hasTitle = first != null && sorted.size > 1 &&
                    (first.height > median * 1.1f || (first.text.length < 60 && first.text.trim().lastOrNull()?.let { it !in ".,;:" } == true))
                val bodyLines = if (hasTitle) sorted.drop(1) else sorted
                LayoutBlock(
                    type = BlockType.BOX,
                    title = if (hasTitle) first!!.text.trim() else null,
                    text = TextCleaner.paragraphs(bodyLines).joinToString("\n\n"),
                    box = box,
                    manual = manual,
                )
            }
            else -> LayoutBlock(
                type = BlockType.FIGURE,
                description = if (sorted.isEmpty()) null else "Tekst i figuren: " + sorted.joinToString(", ") { it.text.trim() },
                box = box,
                manual = manual,
            )
        }
    }

    /** Erstatter hevede fotnotetall med «[^n]». */
    internal fun markFootnoteRefs(line: OcrLine): String {
        var text = line.text
        // Unicode-hevet skrift.
        if (text.any { it in superscripts }) {
            text = Regex("([⁰¹²³⁴⁵⁶⁷⁸⁹]+)").replace(text) { m -> "[^" + m.value.map { superscripts.getValue(it) }.joinToString("") + "]" }
        }
        // Egne, små og hevede tall etter et ord.
        val els = line.elements
        if (els.size >= 2) {
            val medianH = els.map { it.height }.median()
            for ((i, e) in els.withIndex()) {
                if (i == 0) continue
                val t = e.text.trim()
                if (t.length in 1..2 && t.all(Char::isDigit) && e.height < medianH * 0.7f && e.bottom < line.bottom - line.height * 0.2f) {
                    text = text.replace(Regex("(?<=\\S) ?" + Regex.escape(t) + "(?=\\s|$)"), "[^$t]")
                }
            }
        }
        // Tall limt rett på et ord på en måte som ikke ellers forekommer i løpende tekst: «ordet.3».
        text = text.split(' ').joinToString(" ") { token ->
            val m = gluedRef.matchEntire(token)
            val word = m?.groupValues?.get(1)
            if (m != null && word != null && word.count { it.isLetter() } >= 3 && word.any { it in ".,;:!?»”\"’)" }) {
                word + "[^" + m.groupValues[2] + "]" + m.groupValues[3]
            } else token
        }
        return text
    }

    private fun footnoteBlocks(lines: List<OcrLine>): List<LayoutBlock> {
        val start = Regex("^\\s*(\\[\\^)?([0-9]{1,3}|[*†‡])\\]?[.)]?\\s+(.*)$")
        val result = mutableListOf<LayoutBlock>()
        for (l in lines.sortedBy { it.top }) {
            val m = start.matchEntire(l.text)
            if (m != null) {
                result += LayoutBlock(BlockType.FOOTNOTE, text = m.groupValues[3].trim(), number = m.groupValues[2], box = Box(l.left, l.top, l.right, l.bottom))
            } else if (result.isNotEmpty()) {
                val last = result.removeAt(result.size - 1)
                result += last.copy(text = TextCleaner.joinLines(last.text, l.text))
            } else {
                return emptyList() // ikke fotnoter likevel
            }
        }
        return result
    }

    /** Grupperer linjer som ligger tett (loddrett avstand under [gap]). */
    private fun groupByProximity(lines: List<OcrLine>, gap: Float = 0.04f): List<List<OcrLine>> {
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (l in lines.sortedBy { it.top }) {
            val g = groups.lastOrNull()
            if (g != null && l.top - g.maxOf { it.bottom } < gap) g += l else groups += mutableListOf(l)
        }
        return groups
    }

    private fun Box.pad(p: Float) = Box((left - p).coerceAtLeast(0f), (top - p).coerceAtLeast(0f), (right + p).coerceAtMost(1f), (bottom + p).coerceAtMost(1f))

    /**
     * Ser på sidene i rekkefølge: markerer avsnitt som fortsetter over sideskift, og flytter resten av et ord
     * som er delt over sideskiftet opp på forrige side.
     */
    fun linkPages(layouts: List<Pair<Long, PageLayout>>): List<Pair<Long, PageLayout>> {
        val result = layouts.toMutableList()
        for (i in result.indices) {
            val (id, layout) = result[i]
            val next = result.getOrNull(i + 1)?.second
            val lastText = layout.blocks.lastOrNull { it.type == BlockType.PARAGRAPH }
            val firstNext = next?.blocks?.firstOrNull { it.type in setOf(BlockType.PARAGRAPH, BlockType.HEADING) }
            var updated = layout
            var updatedNext = next
            if (lastText != null && firstNext?.type == BlockType.PARAGRAPH) {
                val t = lastText.text.trimEnd()
                val endsOpen = t.isNotEmpty() && t.last() !in ".!?:»”\"…)"
                val nextLower = firstNext.text.firstOrNull()?.isLowerCase() == true
                if ((endsOpen || nextLower) && !layout.continuesToNext && layout.source == PageLayout.SOURCE_MLKIT) {
                    updated = updated.copy(continuesToNext = true)
                    updatedNext = updatedNext!!.copy(continuesFromPrevious = true)
                }
                // Orddeling over sideskift.
                if (t.length > 1 && t.last() in "-‐¬" && t[t.length - 2].isLetter() && nextLower) {
                    val end = firstNext.text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) firstNext.text.length else it }
                    val joined = TextCleaner.joinLines(t, firstNext.text.substring(0, end))
                    updated = updated.copy(blocks = updated.blocks.map { if (it === lastText) it.copy(text = joined) else it })
                    val rest = firstNext.text.substring(end).trimStart()
                    updatedNext = updatedNext!!.copy(
                        blocks = updatedNext.blocks.mapNotNull { if (it === firstNext) it.copy(text = rest).takeIf { rest.isNotEmpty() } else it },
                    )
                }
            }
            result[i] = id to updated
            if (updatedNext != null) result[i + 1] = result[i + 1].first to updatedNext
        }
        return result
    }
}
