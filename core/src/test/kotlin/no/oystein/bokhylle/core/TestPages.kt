package no.oystein.bokhylle.core

/** Lager en syntetisk side: brødtekst med jevn linjeavstand, valgfri topptekst og sidetall. */
internal object TestPages {
    const val LH = 0.02f // linjehøyde
    const val STEP = 0.03f // linjeavstand
    const val LEFT = 0.1f
    const val RIGHT = 0.9f

    fun line(text: String, top: Float, left: Float = LEFT, right: Float = RIGHT, height: Float = LH, block: Int = 1) =
        OcrLine(text, left, top, right, top + height, block)

    fun page(
        body: List<String>,
        header: String? = null,
        number: String? = null,
        numberAtTop: Boolean = false,
        indents: Set<Int> = emptySet(),
        shortLines: Set<Int> = emptySet(),
    ): List<OcrLine> {
        val lines = mutableListOf<OcrLine>()
        if (header != null) lines += line(header, 0.05f, left = 0.3f, right = 0.7f, block = 0)
        if (number != null && numberAtTop) lines += line(number, 0.05f, left = 0.85f, right = 0.88f, block = 0)
        var y = 0.1f
        body.forEachIndexed { i, text ->
            val left = if (i in indents) LEFT + 0.04f else LEFT
            val right = if (i in shortLines) 0.5f else RIGHT
            lines += line(text, y, left = left, right = right)
            y += STEP
        }
        // Fyll ut siden så topp-/bunnsonene blir realistisk smale.
        var k = 0
        while (y < 0.85f) { lines += line("fyll ${letters((body.hashCode() * 31 + k++) * -1640531535)} midt på siden", y); y += STEP }
        if (number != null && !numberAtTop) lines += line(number, 0.92f, left = 0.48f, right = 0.52f, block = 9)
        return lines
    }

    /** Ulik tekst per side og linje (bare bokstaver, siden sifre ikke skiller topptekster). */
    private fun letters(n: Int) = Integer.toString(n and 0x7fffffff, 26).map { c -> 'a' + Character.digit(c, 26) }.joinToString("")
}
