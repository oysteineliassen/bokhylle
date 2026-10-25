package no.oystein.bokhylle.core

enum class BlockType(val json: String) {
    HEADING("heading"),
    PARAGRAPH("paragraph"),
    /** Faktaboks/digresjon med egen tittel. */
    BOX("box"),
    FIGURE("figure"),
    CAPTION("caption"),
    /** Fotnotetekst (nederst på siden eller i en notedel). Referansene står i teksten som «[^n]». */
    FOOTNOTE("footnote"),
    ;

    companion object {
        fun of(name: String?): BlockType? = entries.firstOrNull { it.json == name }
    }
}

/**
 * Én blokk på en side. Fotnotereferanser står i [text] som «[^3]».
 * [box] er plasseringen på det beskårne sidebildet, normalisert 0–1.
 */
data class LayoutBlock(
    val type: BlockType,
    val text: String = "",
    val title: String? = null,
    val description: String? = null,
    val number: String? = null,
    val box: Box? = null,
    /** Utklipt figurbilde (filsti), eller tegningen når figuren er tegnet på nytt. */
    val image: String? = null,
    /** Fotoet av figuren når [image] er en ny tegning, så man kan bytte tilbake. */
    val photo: String? = null,
    /** Tegningen som er lagt til side når brukeren har valgt fotoet igjen, så den kan tas i bruk igjen. */
    val drawing: String? = null,
    /** Satt når blokken er markert av brukeren. */
    val manual: Boolean = false,
    /** Figurtypen fra analysen (null for eldre analyser og ML Kit). */
    val kind: FigureKind? = null,
    /** Brukerens valg «ta med / utelat i tegneeksport»; null = etter figurtypen (se [FigureKind.shouldRedraw]). */
    val redraw: Boolean? = null,
)

/** Sideoppsettet leseren viser: blokkene i leserekkefølge, og det som ikke hører til brødteksten for seg. */
data class PageLayout(
    val blocks: List<LayoutBlock>,
    val pageNumber: String? = null,
    val header: String? = null,
    val footer: String? = null,
    /** Første avsnitt fortsetter fra forrige side. */
    val continuesFromPrevious: Boolean = false,
    /** Siste avsnitt fortsetter på neste side. */
    val continuesToNext: Boolean = false,
    val source: String = SOURCE_MLKIT,
) {
    fun encode(): String = Json.write(
        mapOf(
            "page_number" to pageNumber,
            "running_header" to header,
            "running_footer" to footer,
            "continues_from_previous" to continuesFromPrevious,
            "continues_to_next" to continuesToNext,
            "source" to source,
            "blocks" to blocks.map { b ->
                mapOf(
                    "type" to b.type.json, "text" to b.text.ifEmpty { null }, "title" to b.title,
                    "description" to b.description, "number" to b.number, "bbox" to b.box?.toList(),
                    "image" to b.image, "photo" to b.photo, "drawing" to b.drawing, "manual" to (if (b.manual) true else null),
                    "kind" to b.kind?.json, "redraw" to b.redraw,
                )
            },
        ),
    )

    /** Ren tekst, f.eks. for søk: uten fotnotemerker, figurer som beskrivelse. */
    fun plainText(): String = blocks.joinToString("\n\n") { b ->
        when (b.type) {
            BlockType.BOX -> listOfNotNull(b.title, b.text).joinToString("\n")
            BlockType.FIGURE -> b.description.orEmpty()
            BlockType.FOOTNOTE -> "${b.number ?: ""} ${b.text}".trim()
            else -> b.text
        }.replace(FOOTNOTE_REF, "")
    }.trim()

    companion object {
        const val SOURCE_MLKIT = "mlkit"
        const val SOURCE_CLAUDE = "claude"

        val FOOTNOTE_REF = Regex("\\[\\^([^\\]\\s]{1,4})]")

        fun decode(text: String?): PageLayout? {
            val root = runCatching { Json.parse(text ?: return null).obj() }.getOrNull() ?: return null
            return fromJson(root, root.str("source") ?: SOURCE_MLKIT)
        }

        /** Leser oppsettet fra et JSON-objekt (også Claudes svar, som har samme feltnavn). */
        fun fromJson(root: Map<String, Any?>, source: String): PageLayout {
            val blocks = root["blocks"].arr().mapNotNull { it.obj() }.mapNotNull { o ->
                val type = BlockType.of(o.str("type")) ?: return@mapNotNull null
                // Overskrift med kapittelnummeret i "text" og tittelen i "title" («35» + «Hyppige måltider …»):
                // leseren viser bare teksten, så tittelen legges til der.
                if (type == BlockType.HEADING) {
                    val text = o.str("text").orEmpty().trim()
                    val title = o.str("title")?.trim().orEmpty()
                    if (title.isNotEmpty() && !text.contains(title)) {
                        return@mapNotNull LayoutBlock(
                            type, listOf(text, title).filter { it.isNotEmpty() }.joinToString(" "),
                            box = Box.of(o["bbox"])?.let(::normalizeBox), manual = o.bool("manual"),
                        )
                    }
                }
                LayoutBlock(
                    type = type,
                    text = o.str("text").orEmpty(),
                    title = o.str("title")?.takeIf { it.isNotBlank() },
                    description = o.str("description")?.takeIf { it.isNotBlank() },
                    number = o.str("number") ?: o.num("number")?.toInt()?.toString(),
                    box = Box.of(o["bbox"])?.let(::normalizeBox),
                    image = o.str("image"),
                    photo = o.str("photo"),
                    drawing = o.str("drawing"),
                    manual = o.bool("manual"),
                    kind = FigureKind.of(o.str("kind")),
                    redraw = o["redraw"] as? Boolean,
                )
            }
            return PageLayout(
                blocks = blocks,
                pageNumber = root.str("page_number")?.takeIf { it.isNotBlank() } ?: root.num("page_number")?.toInt()?.toString(),
                header = root.str("running_header")?.takeIf { it.isNotBlank() },
                footer = root.str("running_footer")?.takeIf { it.isNotBlank() },
                continuesFromPrevious = root.bool("continues_from_previous"),
                continuesToNext = root.bool("continues_to_next"),
                source = source,
            )
        }

        /** Sikrer at x0<x1, y0<y1 og at alt er innenfor 0–1. */
        private fun normalizeBox(b: Box): Box? {
            val l = minOf(b.left, b.right).coerceIn(0f, 1f)
            val r = maxOf(b.left, b.right).coerceIn(0f, 1f)
            val t = minOf(b.top, b.bottom).coerceIn(0f, 1f)
            val bo = maxOf(b.top, b.bottom).coerceIn(0f, 1f)
            return if (r - l < 0.005f || bo - t < 0.005f) null else Box(l, t, r, bo)
        }
    }
}
