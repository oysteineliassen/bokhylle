package no.oystein.bokhylle.core

/**
 * Kapittelnummer i stor skrift over kapitteltittelen («43» over «Tidsbegrenset spising») ser Claude av og til på som
 * pynt og utelater, eller leser feil («08» for 06, «12» for 112). Rettes med telefonens tekstgjenkjenning og
 * nummereringen i boka:
 * - et stort tall rett over overskriften settes først i overskriften («43 Tidsbegrenset spising»);
 * - nummer som egen overskrift slås sammen med tittelen («22» + «Et spekter av ketose»);
 * - overskrift med bare nummeret (eller tom) får tittelen fra tekstgjenkjenningen;
 * - feillest eller manglende nummer rettes/fylles inn når nabokapitlene viser hva det må være (25, ?, 27 → 26).
 */
object ChapterNumbers {
    private val number = Regex("""^\d{1,3}$""")
    private val numberOnly = Regex("""^\d{1,3}\.?$""")
    private val leadingNumber = Regex("""^(\d{1,3})\s""")

    /** Én side (uten nabosidene til hjelp ved feillest tall). */
    fun restore(layout: PageLayout, lines: List<OcrLine>): PageLayout = restoreBook(listOf(layout to lines)).single()

    /** Hele boka i leserekkefølge: oppsett og telefonens tekstlinjer for hver side. */
    fun restoreBook(pages: List<Pair<PageLayout, List<OcrLine>>>): List<PageLayout> {
        // Overskrifter der det står et nummer vi ikke kan lese: (side, blokk), fylles inn fra nummereringen.
        val unresolved = mutableSetOf<Pair<Int, Int>>()
        val layouts = pages.mapIndexed { pi, (layout, lines) ->
            val (l, missing) = restorePage(mergeNumberHeadings(layout), lines)
            missing?.let { unresolved += pi to it }
            restoreTitles(l, lines)
        }
        return sequence(layouts, unresolved)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Vanlig teksthøyde på siden: medianen av ordenes høyde. Linjehøyden duger ikke på skjeve foto (rammen rundt en
     * lang, skrå linje blir mye høyere enn bokstavene).
     */
    private fun wordHeight(lines: List<OcrLine>): Float {
        val words = lines.flatMap { it.elements }.map { it.height }.filter { it > 0f }
        return (words.ifEmpty { lines.map { it.height } }).median()
    }

    /** Setter inn nummeret over overskriften på én side. Andre verdi: blokken med et nummer som ikke kunne leses. */
    private fun restorePage(layout: PageLayout, lines: List<OcrLine>): Pair<PageLayout, Int?> {
        if (lines.isEmpty()) return layout to null
        val median = wordHeight(lines)
        if (median <= 0f) return layout to null
        // Står tallet allerede som egen blokk, er det med.
        if (layout.blocks.any { number.matches(it.text.trim()) }) return layout to null
        layout.blocks.forEachIndexed { i, b ->
            val box = b.box
            if (b.type != BlockType.HEADING || box == null || b.text.firstOrNull()?.isDigit() == true) return@forEachIndexed
            val line = lines.firstOrNull { l ->
                val t = l.text.trim()
                // Kort tekst (et tall, kanskje feillest) i stor skrift: kapittelnummer, ikke sidetall eller fotnotetall.
                t.isNotEmpty() && t.length <= 4 && !t.contains(' ') && l.height > median * 1.3f &&
                    // Rett over overskriften (eller på samme linje, foran den).
                    l.top < box.top + box.height * 0.5f && box.top - l.bottom < l.height * 1.5f && l.bottom <= box.bottom &&
                    // Samme spalte: begynner nær overskriftens venstrekant eller overlapper den.
                    l.left < box.right && l.right > box.left - box.width * 0.2f
            }
            if (line == null) {
                // Tallet er ikke lest i det hele tatt, men overskriftens ramme rekker godt over tittelen: der står det.
                val title = b.text.trim().takeIf { it.isNotEmpty() }?.let { t ->
                    lines.filter { l -> l.top >= box.top - median && l.top < box.bottom && norm(l.text).let { it.length >= 4 && norm(t).startsWith(it.take(12)) } }
                        .minByOrNull { it.top }
                }
                if (title != null && title.top - box.top > median * 1.5f &&
                    lines.none { l -> l !== title && l.bottom > box.top && l.top < title.top - median * 0.3f && l.right > box.left && l.left < box.right }
                ) return layout to i
                return@forEachIndexed
            }
            val n = PageNumberDetector.parseToken(line.text, allowRoman = false)?.value?.takeIf { it in 1..999 }
                ?: return layout to i
            // «4.» beholdes med punktum (som i boka); feillest tall («Z6») skrives som tallet.
            val label = line.text.trim().takeIf { numberOnly.matches(it) } ?: n.toString()
            return layout.copy(blocks = layout.blocks.mapIndexed { k, x -> if (k == i) x.copy(text = "$label ${x.text}") else x }) to null
        }
        return layout to null
    }

    /**
     * Nummeret som egen overskrift rett før tittelen («22» + «Et spekter av ketose», «1.» + «HVA SKJER …») slås
     * sammen til én overskrift; står nummeret allerede i tittelen («12.» + «12. TIL DEG …»), fjernes det doble.
     */
    private fun mergeNumberHeadings(layout: PageLayout): PageLayout {
        val out = mutableListOf<LayoutBlock>()
        val blocks = layout.blocks
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val next = blocks.getOrNull(i + 1)
            val n = b.text.trim()
            if (b.type == BlockType.HEADING && numberOnly.matches(n) && next?.type == BlockType.HEADING &&
                next.text.isNotBlank() && !numberOnly.matches(next.text.trim())
            ) {
                val title = next.text.trim()
                val merged = if (title.startsWith(n.trimEnd('.') + ".") || title.startsWith(n.trimEnd('.') + " ")) title else "$n $title"
                val box = b.box?.let { a -> next.box?.let { a.union(it) } ?: a } ?: next.box
                out += next.copy(text = merged, box = box)
                i += 2
                continue
            }
            out += b
            i++
        }
        return if (out.size == blocks.size) layout else layout.copy(blocks = out)
    }

    /**
     * Overskrift med bare et kapittelnummer: tittelen fra linjen rett under nummeret. Tom overskrift: teksten som
     * står innenfor rammen.
     */
    private fun restoreTitles(layout: PageLayout, lines: List<OcrLine>): PageLayout {
        if (lines.isEmpty()) return layout
        val median = wordHeight(lines)
        var changed = false
        val blocks = layout.blocks.mapIndexed { i, b ->
            val box = b.box
            if (b.type != BlockType.HEADING || box == null) return@mapIndexed b
            val next = layout.blocks.getOrNull(i + 1)
            if (b.text.isBlank()) {
                val inside = lines.filter { l ->
                    l.text.any(Char::isLetterOrDigit) && (l.top + l.bottom) / 2 in box.top..box.bottom && l.right > box.left && l.left < box.right
                }.sortedBy { it.top }
                if (inside.isEmpty()) return@mapIndexed b
                changed = true
                return@mapIndexed b.copy(text = inside.joinToString(" ") { it.text.trim() })
            }
            if (!number.matches(b.text.trim())) return@mapIndexed b
            // Står tittelen som neste blokk (egen overskrift), er den med.
            if (next?.type == BlockType.HEADING) return@mapIndexed b
            val title = lines.filter { l ->
                l.text.any(Char::isLetter) && l.top >= box.bottom - box.height * 0.3f && l.top - box.bottom < box.height * 1.5f &&
                    l.height > median * 1.1f && l.left < box.right + box.width * 4f && l.right > box.left
            }.minByOrNull { it.top } ?: return@mapIndexed b
            val text = title.text.trim()
            // Ikke hvis linjen er begynnelsen av brødteksten under.
            if (next != null && norm(next.text).startsWith(norm(text))) return@mapIndexed b
            changed = true
            b.copy(text = "${b.text.trim()} $text")
        }
        return if (changed) layout.copy(blocks = blocks) else layout
    }

    /**
     * Nummereringen i boka: feillest nummer rettes og manglende fylles inn når nabokapitlene viser hva det må være.
     * - «08» mellom 05 og 07 → 06 (forrige + 2 = neste);
     * - «12» etter 111 → 112, «17» etter 116 → 117 (første siffer falt bort);
     * - ulest nummer mellom 25 og 27 → 26 (bare når hullet passer nøyaktig med antall ukjente).
     */
    private fun sequence(layouts: List<PageLayout>, unresolved: Set<Pair<Int, Int>>): List<PageLayout> {
        class Item(val page: Int, val block: Int, val token: String?) {
            var number: Int? = token?.toInt()
        }
        val items = layouts.flatMapIndexed { pi, l ->
            l.blocks.mapIndexedNotNull { bi, b ->
                if (b.type != BlockType.HEADING) return@mapIndexedNotNull null
                val m = leadingNumber.find(b.text.trim() + " ")
                when {
                    m != null -> Item(pi, bi, m.groupValues[1])
                    (pi to bi) in unresolved -> Item(pi, bi, null)
                    else -> null
                }
            }
        }
        if (items.none { it.token == null } && items.size < 3) return layouts
        val texts = mutableMapOf<Pair<Int, Int>, String>()

        // Rett feillest nummer.
        var last: Int? = null
        var missing = 0
        items.forEachIndexed { k, it ->
            val token = it.token
            if (token == null) { missing++; return@forEachIndexed }
            val raw = token.toInt()
            val prev = last
            if (prev != null) {
                val want = prev + 1 + missing
                val next = items.drop(k + 1).firstOrNull { x -> x.token != null }?.token
                val wantNext = want + 1 + items.drop(k + 1).takeWhile { x -> x.token == null }.size
                val nextFits = next == null || next.toInt() == wantNext || (next.length < wantNext.toString().length && wantNext.toString().endsWith(next))
                val truncated = token.length >= 2 && want.toString().length > token.length && want.toString().endsWith(token)
                val between = missing == 0 && next != null && next.toInt() == prev + 2
                if (raw != want && ((truncated && nextFits) || between)) {
                    it.number = want
                    val b = layouts[it.page].blocks[it.block]
                    texts[it.page to it.block] = want.toString().padStart(token.length, '0') + b.text.trim().removePrefix(token)
                }
            }
            last = it.number
            missing = 0
        }

        // Fyll inn manglende nummer der hullet passer.
        var start = -1
        items.forEachIndexed { k, it ->
            if (it.token != null) { start = k; return@forEachIndexed }
            val first = items.indexOfFirst { x -> x.token == null && items.indexOf(x) > start }
            if (first != k) return@forEachIndexed
            val end = (k until items.size).firstOrNull { x -> items[x].token != null } ?: return@forEachIndexed
            val prev = items.getOrNull(start)?.number ?: return@forEachIndexed
            val next = items[end].number ?: return@forEachIndexed
            if (next - prev - 1 != end - k) return@forEachIndexed
            for (x in k until end) {
                val b = layouts[items[x].page].blocks[items[x].block]
                texts[items[x].page to items[x].block] = "${prev + 1 + (x - k)} ${b.text.trim()}"
            }
        }
        if (texts.isEmpty()) return layouts
        return layouts.mapIndexed { pi, l ->
            if (texts.keys.none { it.first == pi }) l
            else l.copy(blocks = l.blocks.mapIndexed { bi, b -> texts[pi to bi]?.let { b.copy(text = it) } ?: b })
        }
    }
}
