package no.oystein.bokhylle.core

/**
 * Kapittelnummer i stor skrift over kapitteltittelen («43» over «Tidsbegrenset spising») ser Claude av og til
 * på som pynt og utelater. Telefonens tekstgjenkjenning har det som en egen, stor linje rett over overskriften;
 * da settes nummeret tilbake først i overskriften («43 Tidsbegrenset spising»). Er tallet lest feil (lyst grått
 * tall blir «Z6» eller «2б»), brukes nummereringen: står det kapittel 25 før og 27 etter, er det 26.
 * Omvendt kan overskriften bestå av bare nummeret («35») når tittelen under er utelatt; da hentes tittelen fra
 * linjen rett under.
 */
object ChapterNumbers {
    private val number = Regex("""^\d{1,3}$""")
    private val leadingNumber = Regex("""^(\d{1,3})\s""")

    /** Én side (uten nabosidene til hjelp ved feillest tall). */
    fun restore(layout: PageLayout, lines: List<OcrLine>): PageLayout = restoreBook(listOf(layout to lines)).single()

    /** Hele boka i leserekkefølge: oppsett og telefonens tekstlinjer for hver side. */
    fun restoreBook(pages: List<Pair<PageLayout, List<OcrLine>>>): List<PageLayout> {
        // Overskrifter med et stort, uleselig tegn over: (side, blokk), fylles inn fra nummereringen etterpå.
        val unresolved = mutableListOf<Pair<Int, Int>>()
        val layouts = pages.mapIndexed { pi, (layout, lines) ->
            val (l, missing) = restorePage(layout, lines)
            missing?.let { unresolved += pi to it }
            l
        }.toMutableList()
        if (unresolved.isNotEmpty()) {
            // Kapittelnumrene i boka i rekkefølge: (side, blokk, nummer).
            val numbered = layouts.flatMapIndexed { pi, l ->
                l.blocks.mapIndexedNotNull { bi, b ->
                    if (b.type != BlockType.HEADING) null
                    else leadingNumber.find(b.text.trim() + " ")?.groupValues?.get(1)?.toIntOrNull()?.let { Triple(pi, bi, it) }
                }
            }
            fun before(a: Pair<Int, Int>, b: Triple<Int, Int, Int>) = a.first < b.first || (a.first == b.first && a.second < b.second)
            val groups = unresolved.groupBy { u -> numbered.lastOrNull { !before(u, it) }?.third to numbered.firstOrNull { before(u, it) }?.third }
            for ((bounds, group) in groups) {
                val (prev, next) = bounds
                // Bare når hullet i nummereringen passer nøyaktig med antall overskrifter uten nummer.
                if (prev == null || next == null || next - prev - 1 != group.size) continue
                group.forEachIndexed { k, (pi, bi) ->
                    val l = layouts[pi]
                    layouts[pi] = l.copy(blocks = l.blocks.mapIndexed { i, b -> if (i == bi) b.copy(text = "${prev + 1 + k} ${b.text}") else b })
                }
            }
        }
        return layouts.mapIndexed { pi, l -> restoreTitles(l, pages[pi].second) }
    }

    /** Setter inn nummeret over overskriften på én side. Andre verdi: blokken med et stort tegn som ikke kunne leses. */
    private fun restorePage(layout: PageLayout, lines: List<OcrLine>): Pair<PageLayout, Int?> {
        if (lines.isEmpty()) return layout to null
        val median = lines.map { it.height }.median()
        if (median <= 0f) return layout to null
        // Står tallet allerede som egen blokk, er det med.
        val present = layout.blocks.map { it.text.trim() }.filter { number.matches(it) }.toSet()
        if (present.isNotEmpty()) return layout to null
        layout.blocks.forEachIndexed { i, b ->
            val box = b.box
            if (b.type != BlockType.HEADING || box == null || b.text.firstOrNull()?.isDigit() == true) return@forEachIndexed
            val line = lines.firstOrNull { l ->
                val t = l.text.trim()
                // Kort tekst (et tall, kanskje feillest) i stor skrift: kapittelnummer, ikke sidetall eller fotnotetall.
                t.isNotEmpty() && t.length <= 4 && !t.contains(' ') && l.height > median * 1.6f &&
                    // Rett over overskriften (eller på samme linje, foran den).
                    l.top < box.top + box.height * 0.5f && box.top - l.bottom < l.height * 1.5f && l.bottom <= box.bottom &&
                    // Samme spalte: begynner nær overskriftens venstrekant eller overlapper den.
                    l.left < box.right && l.right > box.left - box.width * 0.2f
            } ?: return@forEachIndexed
            val n = PageNumberDetector.parseToken(line.text, allowRoman = false)?.value?.takeIf { it in 1..999 }
                ?: return layout to i
            // «4.» beholdes med punktum (som i boka); feillest tall («Z6») skrives som tallet.
            val label = line.text.trim().takeIf { Regex("""^\d{1,3}\.?$""").matches(it) } ?: n.toString()
            return layout.copy(blocks = layout.blocks.mapIndexed { k, x -> if (k == i) x.copy(text = "$label ${x.text}") else x }) to null
        }
        return layout to null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Overskrift med bare et kapittelnummer: tittelen fra telefonens tekstgjenkjenning rett under nummeret. */
    private fun restoreTitles(layout: PageLayout, lines: List<OcrLine>): PageLayout {
        if (lines.isEmpty()) return layout
        val median = lines.map { it.height }.median()
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
