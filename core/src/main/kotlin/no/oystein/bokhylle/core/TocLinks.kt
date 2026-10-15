package no.oystein.bokhylle.core

/**
 * Oppføringer i en innholdsfortegnelse, så leseren kan gjøre dem trykkbare: linjer med tekst som ender på et
 * sidetall («Kapittel 3: Lys ........ 47»). En side regnes som innholdsfortegnelse når mange av linjene er slik.
 */
object TocLinks {
    /** En oppføring: tegnområdet i teksten som skal kunne trykkes på, og sidetallet den viser til. */
    data class Entry(val range: IntRange, val numberStart: Int, val page: Int)

    private val entry = Regex("""^(.*\p{L}.*?)[\s.·…_\-–]*?\s(\d{1,4})\s*$""")
    private const val MIN_ENTRIES = 4

    /** Oppføringene i én tekstblokk (linje for linje). */
    fun entries(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var start = 0
        for (line in text.split('\n')) {
            val m = entry.find(line)
            if (m != null) {
                val page = m.groupValues[2].toInt()
                val lead = line.length - line.trimStart().length
                val numberStart = start + m.groups[2]!!.range.first
                out += Entry(start + lead..start + line.trimEnd().length - 1, numberStart, page)
            }
            start += line.length + 1
        }
        return out
    }

    /** Er siden en innholdsfortegnelse? Minst fire oppføringer, og de er minst 40 % av linjene med tekst. */
    fun isTocPage(layout: PageLayout): Boolean {
        val lines = layout.blocks.filter { it.type != BlockType.FIGURE && it.type != BlockType.FOOTNOTE }
            .flatMap { b -> b.text.split('\n').filter { it.isNotBlank() } }
        if (lines.isEmpty()) return false
        val hits = lines.count { entry.containsMatchIn(it) }
        return hits >= MIN_ENTRIES && hits >= 0.4f * lines.size
    }

    /**
     * Boksiden et sidetall i innholdsfortegnelsen viser til: siden med akkurat det sidetallet, ellers den nærmeste
     * før (siden med sidetallet kan mangle eller ha fått et utledet tall). null når ingen side har et lavere tall.
     */
    fun targetIndex(page: Int, numbers: List<String?>): Int? {
        val values = numbers.map { it?.trim('(', ')', ' ')?.toIntOrNull() }
        values.indexOfFirst { it == page }.takeIf { it >= 0 }?.let { return it }
        return values.withIndex().filter { (_, v) -> v != null && v < page }.maxByOrNull { it.value!! }?.index
    }
}
