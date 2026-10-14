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
    /** Ordene på linjen med egen posisjon (brukes til å finne hevede fotnotetall). */
    val elements: List<OcrElement> = emptyList(),
) {
    val height: Float get() = bottom - top
    val width: Float get() = right - left
    val centerY: Float get() = (top + bottom) / 2f
}

data class OcrElement(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/** En tekstblokk fra OCR, med det appen fant ut om bildet rundt den. */
data class OcrBlockInfo(
    val index: Int,
    val box: Box,
    /** Blokken går helt ut i bildekanten og er trolig kuttet (del av nabosiden e.l.). */
    val cutByEdge: Boolean = false,
    /** Bakgrunnen bak blokken har en annen farge enn papiret (faktaboks). */
    val colored: Boolean = false,
)

/** Et rektangel, normalisert 0–1. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
    fun toList() = listOf(left, top, right, bottom)

    companion object {
        fun of(v: Any?): Box? {
            val l = v.arr().mapNotNull { (it as? Number)?.toFloat() }
            return if (l.size == 4) Box(l[0], l[1], l[2], l[3]) else null
        }
    }
}

/** Hele OCR-resultatet for én side, slik det lagres i databasen. */
data class OcrPage(val lines: List<OcrLine>, val blocks: List<OcrBlockInfo> = emptyList()) {
    fun encode(): String = Json.write(
        mapOf(
            "v" to 1,
            "lines" to lines.map { l ->
                mapOf(
                    "t" to l.text, "b" to listOf(l.left, l.top, l.right, l.bottom), "k" to l.block, "c" to l.confidence,
                    "e" to l.elements.map { e -> listOf(e.text, e.left, e.top, e.right, e.bottom) },
                )
            },
            "blocks" to blocks.map { b -> mapOf("i" to b.index, "b" to b.box.toList(), "cut" to b.cutByEdge, "col" to b.colored) },
        ),
    )

    companion object {
        fun decode(text: String?): OcrPage? {
            val root = runCatching { Json.parse(text ?: return null).obj() }.getOrNull() ?: return null
            val lines = root["lines"].arr().mapNotNull { it.obj() }.map { o ->
                val b = Box.of(o["b"]) ?: Box(0f, 0f, 0f, 0f)
                OcrLine(
                    text = o.str("t") ?: "", left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    block = o.num("k")?.toInt() ?: 0, confidence = o.num("c")?.toFloat() ?: 1f,
                    elements = o["e"].arr().mapNotNull { e ->
                        val a = e.arr()
                        val t = a.getOrNull(0) as? String ?: return@mapNotNull null
                        val n = a.drop(1).mapNotNull { (it as? Number)?.toFloat() }
                        if (n.size == 4) OcrElement(t, n[0], n[1], n[2], n[3]) else null
                    },
                )
            }
            val blocks = root["blocks"].arr().mapNotNull { it.obj() }.mapNotNull { o ->
                OcrBlockInfo(o.num("i")?.toInt() ?: return@mapNotNull null, Box.of(o["b"]) ?: return@mapNotNull null, o.bool("cut"), o.bool("col"))
            }
            return OcrPage(lines, blocks)
        }
    }
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
