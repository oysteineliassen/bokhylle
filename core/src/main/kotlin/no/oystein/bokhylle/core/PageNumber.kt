package no.oystein.bokhylle.core

/**
 * Et sidetall slik det står trykt. Romertall (forord o.l.) kommer alltid før vanlige tall.
 */
data class PageNumber(val value: Int, val roman: Boolean = false) : Comparable<PageNumber> {
    override fun compareTo(other: PageNumber): Int =
        compareValuesBy(this, other, { if (it.roman) 0 else 1 }, { it.value })

    val label: String get() = if (roman) Roman.format(value) else value.toString()

    fun next(): PageNumber = copy(value = value + 1)

    override fun toString(): String = label

    companion object {
        /** Tolker tekst skrevet av brukeren, f.eks. «47» eller «xii». */
        fun parse(text: String): PageNumber? {
            val t = text.trim()
            t.toIntOrNull()?.let { return if (it in 0..9999) PageNumber(it) else null }
            return Roman.parse(t)?.let { PageNumber(it, roman = true) }
        }
    }
}

object Roman {
    private val values = listOf(
        1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i",
    )

    fun format(n: Int): String {
        var rest = n
        return buildString {
            for ((v, s) in values) while (rest >= v) { append(s); rest -= v }
        }
    }

    /** Bare kanonisk skrevne romertall godtas («iiii» og «ic» avvises). */
    fun parse(text: String): Int? {
        val t = text.lowercase()
        if (t.isEmpty() || t.length > 9 || t.any { it !in "ivxlcdm" }) return null
        var total = 0
        var i = 0
        while (i < t.length) {
            val v = digit(t[i])
            val next = if (i + 1 < t.length) digit(t[i + 1]) else 0
            if (v < next) { total += next - v; i += 2 } else { total += v; i++ }
        }
        return if (total > 0 && format(total) == t) total else null
    }

    private fun digit(c: Char) = when (c) {
        'i' -> 1; 'v' -> 5; 'x' -> 10; 'l' -> 50; 'c' -> 100; 'd' -> 500; 'm' -> 1000; else -> 0
    }
}
