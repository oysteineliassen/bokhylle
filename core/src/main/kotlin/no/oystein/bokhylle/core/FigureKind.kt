package no.oystein.bokhylle.core

/**
 * Hva slags figur en FIGURE-blokk er. Bare diagrammer, kart, grafer og tegninger tegnes på nytt (figureksport og
 * «Tegn på nytt» for hele boka); fotografier, illustrasjoner og logoer (også sertifiserings- og miljømerker,
 * forlagslogoer, strekkoder/ISBN og QR-koder) beholdes som foto.
 */
enum class FigureKind(val json: String, val redraw: Boolean) {
    DIAGRAM("diagram", true),
    KART("kart", true),
    GRAF("graf", true),
    TEGNING("tegning", true),
    FOTO("foto", false),
    ILLUSTRASJON("illustrasjon", false),
    LOGO("logo", false),
    ;

    companion object {
        fun of(name: String?): FigureKind? = entries.firstOrNull { it.json == name?.trim()?.lowercase() }

        private val logoWords = Regex(
            """(?i)fsc|pefc|svanemerk|\blogo|strekkode|\bisbn\b|\bqr\b|[\p{L}-]+merke(t|r|ne)?\b|\bmerke\b""",
        )

        /** Gjetning ut fra beskrivelsen, for figurer analysert før [FigureKind] fantes: bare logoer kjennes igjen. */
        fun guess(description: String?): FigureKind? =
            if (description != null && logoWords.containsMatchIn(description)) LOGO else null

        /** Figurtypen: fra analysen, ellers gjettet ut fra beskrivelsen (null = ukjent). */
        fun effective(block: LayoutBlock): FigureKind? = block.kind ?: guess(block.description)

        /**
         * Skal figuren tegnes på nytt? Brukerens valg gjelder først; ellers figurtypen. Ukjent type (eldre
         * analyse eller ML Kit uten beskrivelse) tas med, som før.
         */
        fun shouldRedraw(block: LayoutBlock): Boolean = block.redraw ?: (effective(block)?.redraw ?: true)

        /**
         * Indeksene til figurene på en side som skal i tegneeksporten: figurer med bilde og plassering som ikke er
         * tegnet ennå ([isDrawing]) og som skal tegnes på nytt ([shouldRedraw]).
         */
        fun forExport(blocks: List<LayoutBlock>, isDrawing: (String) -> Boolean): List<Int> = blocks.indices.filter { i ->
            val b = blocks[i]
            val image = b.image
            b.type == BlockType.FIGURE && image != null && b.box != null && !isDrawing(image) && shouldRedraw(b)
        }
    }
}
