package no.oystein.bokhylle.core

/**
 * Én tekstlinje fra OCR. Koordinatene er normalisert til 0..1 i forhold til bildets bredde og høyde,
 * slik at logikken ikke avhenger av bildestørrelsen.
 */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Hvilken tekstblokk (avsnitt/område) linjen tilhører i OCR-resultatet. */
    val block: Int = 0,
    val confidence: Float = 1f,
) {
    val height: Float get() = bottom - top
    val width: Float get() = right - left
    val centerY: Float get() = (top + bottom) / 2f
}

/** Råtekst slik OCR leste den: linjer med linjeskift, blokker skilt med blank linje. */
fun rawText(lines: List<OcrLine>): String = buildString {
    var block: Int? = null
    for (line in lines) {
        if (block != null) append(if (line.block != block) "\n\n" else "\n")
        append(line.text)
        block = line.block
    }
}

internal fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val s = sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
}
