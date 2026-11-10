package no.oystein.bokhylle.core

/**
 * Kildelisten bakerst i boka («Kilder», «Noter»): nummererte oppføringer som teksten viser til med hevede tall,
 * også flere på én gang («32,33», «75–77»).
 * - [normalize] gjør sider som bare er en nummerert liste levert som fotnoter, om til vanlig tekst, så de vises.
 * - [lookup] finner teksten til en henvisning: fotnoten på samme side, ellers oppføringen i kildelisten.
 */
object Sources {
    private const val MIN_ENTRIES = 5
    private val numbered = Regex("""^(\d{1,3})[.)]?\s+(\S.*)$""", RegexOption.DOT_MATCHES_ALL)

    private fun content(layout: PageLayout) = layout.blocks.filter { it.type != BlockType.HEADING && it.type != BlockType.FIGURE }

    /** Siden er (en del av) en kildeliste levert som fotnoter: nesten bare fotnoter, og mange av dem. */
    private fun isFootnoteList(layout: PageLayout): Boolean {
        val blocks = content(layout)
        val notes = blocks.count { it.type == BlockType.FOOTNOTE }
        return notes >= MIN_ENTRIES && notes >= 0.7f * blocks.size
    }

    /** Siden er en kildeliste som vanlig tekst: nesten bare avsnitt som begynner med et nummer. */
    private fun isNumberedList(layout: PageLayout): Boolean {
        val blocks = content(layout)
        val entries = blocks.count { it.type == BlockType.PARAGRAPH && numbered.matches(it.text.trim()) }
        return entries >= MIN_ENTRIES && entries >= 0.7f * blocks.size
    }

    /** Kildelistesider levert som fotnoter blir nummererte avsnitt («21. Monteiro, C. A. …»), så leseren viser dem. */
    fun normalize(layouts: List<PageLayout>): List<PageLayout> = layouts.map { l ->
        if (!isFootnoteList(l)) l
        else l.copy(blocks = l.blocks.map { b ->
            if (b.type != BlockType.FOOTNOTE) b
            else b.copy(type = BlockType.PARAGRAPH, text = listOfNotNull(b.number?.let { "$it." }, b.text.trim()).joinToString(" "), number = null)
        })
    }

    /** Oppføringene i kildelisten(e): nummer → tekst. Finnes et nummer flere ganger, gjelder den første. */
    fun index(layouts: List<PageLayout>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (l in layouts) {
            if (isNumberedList(l)) {
                for (b in l.blocks.filter { it.type == BlockType.PARAGRAPH }) {
                    val m = numbered.matchEntire(b.text.trim()) ?: continue
                    out.putIfAbsent(m.groupValues[1], m.groupValues[2].trim())
                }
            }
            if (isFootnoteList(l)) l.blocks.filter { it.type == BlockType.FOOTNOTE }.forEach { b -> b.number?.let { out.putIfAbsent(it, b.text.trim()) } }
        }
        return out
    }

    /** Numrene i en henvisning: «32,33» → 32, 33; «75–77» → 75, 76, 77; «a» → a. */
    fun numbers(ref: String): List<String> = ref.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
        val range = Regex("""^(\d{1,3})\s*[-–—]\s*(\d{1,3})$""").matchEntire(part)
        val from = range?.groupValues?.get(1)?.toInt()
        val to = range?.groupValues?.get(2)?.toInt()
        if (from != null && to != null && to in from..from + 20) (from..to).map { it.toString() } else listOf(part)
    }

    /**
     * Teksten til henvisningen [ref] på siden [pageIndex]: (nummer, tekst) for hvert nummer som finnes. Fotnoter på
     * samme side går foran, så fotnoter på andre sider (nærmeste først), så kildelisten ([index]).
     */
    fun lookup(layouts: List<PageLayout>, pageIndex: Int, ref: String, index: Map<String, String> = index(layouts)): List<Pair<String, String>> {
        fun footnote(page: Int, n: String) = layouts.getOrNull(page)?.blocks?.firstOrNull { it.type == BlockType.FOOTNOTE && it.number == n }?.text?.trim()
        return numbers(ref).mapNotNull { n ->
            val text = footnote(pageIndex, n) ?: index[n]
                ?: ((pageIndex + 1 until layouts.size) + (pageIndex - 1 downTo 0)).firstNotNullOfOrNull { footnote(it, n) }
            text?.let { n to it }
        }
    }
}
