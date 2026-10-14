package no.oystein.bokhylle.core

/** Et sidetall funnet på en side, og hvor det sto. */
data class DetectedPageNumber(
    val number: PageNumber,
    /** Indeks i linjelisten. */
    val lineIndex: Int,
    /** true = tallet sto alene på linjen. false = først/sist i en topptekstlinje («47   KAPITTEL TRE»). */
    val wholeLine: Boolean,
    /** Teksten som skal fjernes fra linjen når [wholeLine] er false. */
    val token: String,
    /** 0..1, hvor sikker gjenkjenningen er. */
    val confidence: Float,
)

/**
 * Finner sidetallet ut fra plasseringen: et frittstående tall i øverste eller nederste ~12 % av området
 * tekstblokkene dekker. Store tall (kapittelnummer i stor skrift) og tall midt på siden ignoreres.
 */
object PageNumberDetector {
    const val ZONE = 0.12f
    private const val MAX_ARABIC = 2000
    private const val MAX_ROMAN = 80

    private val trimChars = "-–—―‒_•·.,:;()[]{}|*\"'«»“”‘’<>~/\\ ".toCharArray()

    fun detect(lines: List<OcrLine>): DetectedPageNumber? {
        if (lines.isEmpty()) return null
        val top = lines.minOf { it.top }
        val bottom = lines.maxOf { it.bottom }
        val span = bottom - top
        if (span <= 0f) return null
        val zone = span * ZONE
        val medianHeight = lines.map { it.height }.median()

        val toc = TocColumns.find(lines)

        val candidates = mutableListOf<Pair<DetectedPageNumber, Float>>()
        lines.forEachIndexed { index, line ->
            // Innholdsfortegnelse: tallene i høyrekolonnen og «Kapittel …… 133» er henvisninger, ikke sidens sidetall.
            if (toc != null && (index in toc.entryLines || toc.inColumn(line))) return@forEachIndexed
            val inTop = line.centerY <= top + zone
            val inBottom = line.centerY >= bottom - zone
            if (!inTop && !inBottom) return@forEachIndexed
            // Kapittelnummer står ofte i stor skrift øverst på siden; sidetall er sjelden større enn brødteksten.
            if (medianHeight > 0f && line.height > medianHeight * 1.6f && lines.size > 3) return@forEachIndexed
            // Hvor nær kanten av tekstområdet linjen er (1 = helt ytterst).
            val edge = if (inTop) 1f - (line.top - top) / zone else 1f - (bottom - line.bottom) / zone
            val tokens = line.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

            parseToken(line.text, allowRoman = true)?.let { n ->
                candidates += DetectedPageNumber(n, index, true, line.text, line.confidence) to (2f + edge)
            }
            if (tokens.size >= 2 && toc == null) {
                // Topptekst med sidetall i ytterkant: tall først eller sist, aldri romertall (for lett å forveksle med ord).
                for (token in listOf(tokens.first(), tokens.last())) {
                    val n = parseToken(token, allowRoman = false) ?: continue
                    // Krev at resten ser ut som tekst, ikke en tabell eller et årstall midt i en setning.
                    val rest = tokens - token
                    if (rest.none { it.any(Char::isLetter) }) continue
                    if (n.value in 1000..2100 && token.length == 4) continue
                    candidates += DetectedPageNumber(n, index, false, token, line.confidence * 0.8f) to (1f + edge)
                }
            }
        }
        val best = candidates.maxByOrNull { it.second } ?: return null
        val (found, score) = best
        val confidence = (found.confidence * (0.6f + 0.4f * (score - score.toInt()))).coerceIn(0f, 1f)
        return found.copy(confidence = confidence)
    }

    /** Tolker ett tall, med rettelse for typiske OCR-feil (O→0, l/I→1) når det ellers er sifre i ordet. */
    fun parseToken(raw: String, allowRoman: Boolean): PageNumber? {
        val t = raw.trim().trim(*trimChars)
        if (t.isEmpty() || t.length > 6 || t.contains(' ')) return null
        if (t.all(Char::isDigit)) {
            val v = t.toIntOrNull() ?: return null
            return if (v in 1..MAX_ARABIC) PageNumber(v) else null
        }
        if (t.any(Char::isDigit)) {
            val fixed = t.map {
                when (it) {
                    'O', 'o', 'D' -> '0'
                    'l', 'I', '|', 'i', '!' -> '1'
                    'S', 's' -> '5'
                    'B' -> '8'
                    'Z', 'z' -> '2'
                    else -> it
                }
            }.joinToString("")
            if (fixed.all(Char::isDigit)) {
                val v = fixed.toIntOrNull() ?: return null
                return if (v in 1..MAX_ARABIC) PageNumber(v) else null
            }
            return null
        }
        if (allowRoman) {
            // Store bokstaver godtas også, men ikke blandet (f.eks. «Mix»).
            if (t != t.lowercase() && t != t.uppercase()) return null
            val v = Roman.parse(t) ?: return null
            if (v in 1..MAX_ROMAN) return PageNumber(v, roman = true)
        }
        return null
    }
}

/**
 * Kjenner igjen en innholdsfortegnelse (eller et register): mange linjer som ender på et sidetall,
 * eller en kolonne av frittstående tall langs samme høyrekant.
 */
internal class TocColumns private constructor(
    /** Linjer som er en oppføring med tall til slutt («Innledning ...... 17»). */
    val entryLines: Set<Int>,
    /** Høyrekantene til kolonner av frittstående tall. */
    private val columnRights: List<Float>,
) {
    fun inColumn(line: OcrLine): Boolean = columnRights.any { kotlin.math.abs(line.right - it) <= ALIGN }

    companion object {
        /** Hvor nær høyrekantene må være for å regnes som samme kolonne (andel av bildebredden). */
        private const val ALIGN = 0.02f
        private const val MIN_ENTRIES = 5
        private const val MIN_COLUMN = 4

        fun find(lines: List<OcrLine>): TocColumns? {
            val entries = mutableSetOf<Int>()
            val standalone = mutableListOf<OcrLine>()
            lines.forEachIndexed { i, line ->
                val tokens = line.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isEmpty()) return@forEachIndexed
                val last = PageNumberDetector.parseToken(tokens.last(), allowRoman = false)
                when {
                    tokens.size == 1 && last != null -> standalone += line
                    last != null && tokens.dropLast(1).any { t -> t.any(Char::isLetter) } -> entries += i
                }
            }
            // Kolonner: frittstående tall som står under hverandre med samme høyrekant.
            val columns = mutableListOf<Float>()
            for (line in standalone.sortedBy { it.right }) {
                if (columns.any { kotlin.math.abs(line.right - it) <= ALIGN }) continue
                val aligned = standalone.count { kotlin.math.abs(it.right - line.right) <= ALIGN }
                if (aligned >= MIN_COLUMN) columns += line.right
            }
            if (entries.size < MIN_ENTRIES && columns.isEmpty()) return null
            return TocColumns(entries, columns)
        }
    }
}
