package no.oystein.bokhylle.core

/**
 * Kapittelnummer i stor skrift over kapitteltittelen («43» over «Tidsbegrenset spising») ser Claude av og til
 * på som pynt og utelater. Telefonens tekstgjenkjenning har det som en egen, stor linje rett over overskriften;
 * da settes nummeret tilbake først i overskriften («43 Tidsbegrenset spising»). Omvendt kan overskriften bestå av
 * bare nummeret («35») når tittelen under er utelatt; da hentes tittelen fra linjen rett under.
 */
object ChapterNumbers {
    private val number = Regex("""^\d{1,3}$""")

    fun restore(layout: PageLayout, lines: List<OcrLine>): PageLayout {
        if (lines.isEmpty()) return layout
        val median = lines.map { it.height }.median()
        if (median <= 0f) return layout
        // Står tallet allerede som egen blokk, er det med.
        val present = layout.blocks.map { it.text.trim() }.filter { number.matches(it) }.toSet()
        var used = false
        val blocks = layout.blocks.map { b ->
            val box = b.box
            if (used || b.type != BlockType.HEADING || box == null || b.text.firstOrNull()?.isDigit() == true) return@map b
            val line = lines.firstOrNull { l ->
                val t = l.text.trim()
                number.matches(t) && t !in present &&
                    // Stor skrift: kapittelnummer, ikke sidetall eller fotnotetall.
                    l.height > median * 1.6f &&
                    // Rett over overskriften (eller på samme linje, foran den).
                    l.top < box.top + box.height * 0.5f && box.top - l.bottom < l.height * 1.5f &&
                    // Samme spalte: begynner nær overskriftens venstrekant eller overlapper den.
                    l.left < box.right && l.right > box.left - box.width * 0.2f
            } ?: return@map b
            used = true
            b.copy(text = "${line.text.trim()} ${b.text}")
        }
        return restoreTitles(if (used) layout.copy(blocks = blocks) else layout, lines, median)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Overskrift med bare et kapittelnummer: tittelen fra telefonens tekstgjenkjenning rett under nummeret. */
    private fun restoreTitles(layout: PageLayout, lines: List<OcrLine>, median: Float): PageLayout {
        var changed = false
        val blocks = layout.blocks.mapIndexed { i, b ->
            val box = b.box
            if (b.type != BlockType.HEADING || box == null || !number.matches(b.text.trim())) return@mapIndexed b
            // Står tittelen som neste blokk (egen overskrift), er den med.
            val next = layout.blocks.getOrNull(i + 1)
            if (next?.type == BlockType.HEADING) return@mapIndexed b
            val title = lines.filter { l ->
                l.text.any(Char::isLetter) && l.top >= box.bottom - box.height * 0.3f && l.top - box.bottom < box.height * 1.5f &&
                    l.height > median * 1.15f && l.left < box.right + box.width * 4f && l.right > box.left
            }.minByOrNull { it.top } ?: return@mapIndexed b
            val text = title.text.trim()
            // Ikke hvis linjen er begynnelsen av brødteksten under.
            if (next != null && norm(next.text).startsWith(norm(text))) return@mapIndexed b
            changed = true
            b.copy(text = "${b.text.trim()} $text")
        }
        return if (changed) layout.copy(blocks = blocks) else layout
    }
}
