package no.oystein.bokhylle.core

import kotlin.math.max
import kotlin.math.min

/** OCR-linjene for én side, i bokas rekkefølge. */
data class CleanInput(
    val id: Long,
    val lines: List<OcrLine>,
    val pageNumber: DetectedPageNumber?,
)

/**
 * Gjør OCR-linjer om til lesbar tekst:
 * fjerner sidetall og løpende topptekst, slår sammen orddelte ord og bygger avsnitt ut fra innrykk og avstand.
 * Avsnitt skilles med blank linje i resultatet.
 */
object TextCleaner {
    /** En tekst må gå igjen øverst/nederst på minst så mange sider for å regnes som løpende topptekst. */
    const val HEADER_MIN_PAGES = 3

    private val suspendedHyphenWords = setOf("og", "eller", "som", "til", "enn", "samt", "and", "or", "und", "oder")

    fun clean(pages: List<CleanInput>): Map<Long, String> {
        val headers = findRunningHeaders(pages)
        val texts = pages.map { page ->
            val keep = page.lines.filterIndexed { i, _ ->
                val pn = page.pageNumber
                // En linje som hadde sidetallet i ytterkant («47  KAPITTEL TRE») er topptekst i seg selv.
                !(pn != null && pn.lineIndex == i) && (page.id to i) !in headers
            }
            paragraphs(keep).joinToString("\n\n")
        }.toMutableList()

        // Ord delt over sideskift: flytt resten av ordet fra neste side opp.
        for (i in 0 until texts.size - 1) {
            val cur = texts[i]
            val next = texts[i + 1]
            if (endsWithWordHyphen(cur) && next.firstOrNull()?.isLowerCase() == true) {
                val end = next.indexOfFirst { it.isWhitespace() }.let { if (it < 0) next.length else it }
                texts[i] = joinLines(cur, next.substring(0, end))
                texts[i + 1] = next.substring(end).trimStart()
            }
        }
        return pages.indices.associate { pages[it].id to texts[it] }
    }

    /** (side-id, linjeindeks) for linjer som er løpende topptekst eller bunntekst. */
    fun findRunningHeaders(pages: List<CleanInput>): Set<Pair<Long, Int>> {
        data class Candidate(val pageId: Long, val index: Int, val key: String)

        val candidates = mutableListOf<Candidate>()
        for (page in pages) {
            val lines = page.lines
            if (lines.size < 2) continue
            val top = lines.minOf { it.top }
            val bottom = lines.maxOf { it.bottom }
            val zone = (bottom - top) * PageNumberDetector.ZONE
            val medianHeight = lines.map { it.height }.median()
            val byPosition = lines.indices.sortedBy { lines[it].top }
            val edgeLines = (byPosition.take(2).filter { lines[it].centerY <= top + zone } +
                byPosition.takeLast(2).filter { lines[it].centerY >= bottom - zone }).distinct()
            for (i in edgeLines) {
                // Kapitteltitler i stor skrift skal stå, selv om samme tekst er topptekst på de neste sidene.
                if (lines[i].height > medianHeight * 1.3f) continue
                var text = lines[i].text
                val pn = page.pageNumber
                if (pn != null && pn.lineIndex == i && !pn.wholeLine) text = text.replace(pn.token, " ")
                val key = headerKey(text)
                if (key.length >= 3) candidates += Candidate(page.id, i, key)
            }
        }
        // Grupper like tekster, med litt slingringsmonn for OCR-feil.
        val groups = mutableListOf<MutableList<Candidate>>()
        for (c in candidates) {
            val group = groups.firstOrNull { similarity(it.first().key, c.key) >= 0.8 }
            if (group != null) group += c else groups += mutableListOf(c)
        }
        return groups.filter { g -> g.map { it.pageId }.distinct().size >= HEADER_MIN_PAGES }
            .flatten().map { it.pageId to it.index }.toSet()
    }

    /** Bare bokstavene, små: «47 KAPITTEL TRE» og «KAPITTEL TRE 49» blir like. */
    internal fun headerKey(text: String) = text.lowercase().filter { it.isLetter() }

    /** Bygger avsnitt fra linjene på én side. */
    fun paragraphs(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val hasGeometry = lines.any { it.height > 0f }
        if (!hasGeometry) {
            return lines.groupBy { it.block }.values.map { block ->
                block.map { it.text }.reduce(::joinLines).normalizeSpaces()
            }
        }
        val lineHeight = lines.map { it.height }.median()
        val gaps = lines.zipWithNext { a, b -> b.top - a.bottom }.filter { it > -lineHeight / 2 }
        val normalGap = max(gaps.median(), 0f)
        val maxWidth = lines.maxOf { it.width }
        val full = lines.filter { it.width >= maxWidth * 0.7f }
        val left = full.map { it.left }.median()
        val right = full.map { it.right }.median()
        val width = max(right - left, 1e-6f)

        fun isCentered(l: OcrLine) =
            l.left > left + width * 0.08f && l.right < right - width * 0.08f &&
                kotlin.math.abs((l.left - left) - (right - l.right)) < width * 0.08f

        val result = mutableListOf<String>()
        var current: String? = null
        for ((i, line) in lines.withIndex()) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val prev = lines.getOrNull(i - 1)
            val newParagraph = current == null || prev == null || run {
                val gap = line.top - prev.bottom
                val indent = line.left - left
                val prevText = prev.text.trim()
                val prevShort = prev.right < right - width * 0.1f
                val endsSentence = prevText.lastOrNull()?.let { it in ".!?:»\"”…)" } == true
                when {
                    endsWithWordHyphen(prevText) && !prevShort -> false
                    gap > normalGap + lineHeight * 0.7f -> true // blank linje
                    line.top < prev.top - lineHeight / 2 -> true // ny kolonne/blokk lenger opp
                    isCentered(line) || isCentered(prev) -> true // overskrift
                    indent > lineHeight * 0.6f && indent < width * 0.5f -> true // innrykk
                    text.startsWith("– ") || text.startsWith("— ") -> endsSentence // replikk
                    prevShort && endsSentence -> true // kort siste linje i forrige avsnitt
                    else -> false
                }
            }
            if (newParagraph) {
                current?.let { result += it.normalizeSpaces() }
                current = text
            } else {
                current = joinLines(current!!, text)
            }
        }
        current?.let { result += it.normalizeSpaces() }
        return result
    }

    private fun endsWithWordHyphen(text: String): Boolean {
        val t = text.trimEnd()
        if (t.length < 2) return false
        val last = t.last()
        return (last == '-' || last == '‐' || last == '¬' || last == '­') && t[t.length - 2].isLetterOrDigit()
    }

    /** Slår sammen to linjer i samme avsnitt, og orddelingen på linjeslutt («bok-» + «hylle» → «bokhylle»). */
    fun joinLines(prev: String, next: String): String {
        val a = prev.trimEnd()
        val b = next.trimStart()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        if (!endsWithWordHyphen(a)) return "$a $b"
        val last = a.last()
        if (last == '­' || last == '¬') return a.dropLast(1) + b
        val nextWord = b.takeWhile { it.isLetter() }
        // «sommer- og vinterferie»
        if (nextWord.lowercase() in suspendedHyphenWords) return "$a $b"
        val fragment = a.dropLast(1).takeLastWhile { !it.isWhitespace() }
        // «NRK-sjefen», «Nord-Norge», «17-åring»: bindestreken hører til ordet.
        val isAbbreviation = fragment.length >= 2 && fragment.filter { it.isLetter() }.all { it.isUpperCase() }
        if (isAbbreviation || fragment.any { it.isDigit() } || b.first().isUpperCase()) return a + b
        if (b.first().isLowerCase()) return a.dropLast(1) + b
        return a + b
    }

    private fun String.normalizeSpaces() = replace(Regex("[ \\t]+"), " ").trim()

    /** 1 = like, 0 = helt ulike (normalisert Levenshtein). */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        if (min(a.length, b.length) < longest * 0.6) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.length].toDouble() / longest
    }
}
