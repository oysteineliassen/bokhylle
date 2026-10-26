package no.oystein.bokhylle.core

/**
 * Løpende topptekst som Claude av og til leverer som overskrift («FASTEOPPLEGG» øverst på siden, mens de andre
 * sidene i delen har den som topptekst): en overskrift helt øverst på siden, med samme tekst som toppteksten på minst
 * to andre sider, flyttes til toppteksten. Skillesider (bare overskrifter) og tittelsider røres ikke.
 */
object RunningHeaders {
    private const val TOP = 0.12f

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun strip(layouts: List<PageLayout>): List<PageLayout> {
        val counts = layouts.mapNotNull { it.header?.let(::norm) }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        return layouts.map { l ->
            val first = l.blocks.firstOrNull() ?: return@map l
            val box = first.box ?: return@map l
            val key = norm(first.text)
            val ownHeader = l.header?.let(::norm)
            if (first.type != BlockType.HEADING || key.isEmpty() || box.top > TOP) return@map l
            // Minst to andre sider har den som topptekst, og siden har mer innhold enn overskrifter.
            if ((counts[key] ?: 0) - (if (ownHeader == key) 1 else 0) < 2) return@map l
            if (l.blocks.drop(1).none { it.type != BlockType.HEADING }) return@map l
            l.copy(blocks = l.blocks.drop(1), header = l.header ?: first.text.trim())
        }
    }
}
