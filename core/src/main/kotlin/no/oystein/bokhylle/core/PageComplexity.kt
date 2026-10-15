package no.oystein.bokhylle.core

/**
 * Om en side trenger den sterke modellen (Sonnet) eller klarer seg med den billige (Haiku), ut fra det
 * telefonen allerede vet om siden: oppsettet fra ML Kit (figurer, faktabokser, fotnoter), områder brukeren
 * har markert, og store felt uten tekst (typisk et bilde ML Kit ikke har kjent igjen som figur).
 */
object PageComplexity {
    /** Et tomrom mellom tekstlinjer større enn dette (andel av sidehøyden) tyder på et bilde. */
    const val IMAGE_GAP = 0.15f

    fun needsStrongModel(layout: PageLayout?, ocrLines: List<OcrLine>, hasManualRegions: Boolean): Boolean {
        if (hasManualRegions) return true
        val blocks = layout?.blocks.orEmpty()
        if (blocks.any { it.type == BlockType.FIGURE || it.type == BlockType.BOX || it.type == BlockType.FOOTNOTE }) return true
        if (blocks.any { PageLayout.FOOTNOTE_REF.containsMatchIn(it.text) }) return true
        // Nesten ingen tekst på en side som ikke er tom: trolig et bilde, et kart eller en tabell.
        val lines = ocrLines.filter { it.text.isNotBlank() }
        if (lines.size in 1..3) return true
        // Stort tomrom mellom tekstlinjer midt på siden, med tekst under (minst to linjer: ikke bare sidetallet
        // nederst på siste side i et kapittel).
        val sorted = lines.sortedBy { it.top }
        for (i in 1 until sorted.size) {
            if (sorted.size - i >= 2 && sorted[i].top - sorted.subList(0, i).maxOf { it.bottom } > IMAGE_GAP) return true
        }
        return false
    }
}
