package no.oystein.bokhylle.ui.book

import no.oystein.bokhylle.core.PageNumber
import no.oystein.bokhylle.core.PageRanges
import no.oystein.bokhylle.data.Book

/** «1–64, 80–112 av 380»: hvilke sider som er lagt inn, og av hvor mange. */
fun pagesDescription(book: Book, numbers: List<String?>): String? {
    val parsed = numbers.mapNotNull { it?.trim('(', ')')?.let(PageNumber::parse) }
    if (parsed.isEmpty()) return null
    val total = book.antallSiderTotalt ?: parsed.filter { !it.roman }.maxOfOrNull { it.value }
    val ranges = PageRanges.describe(parsed)
    return if (total != null && book.antallSiderTotalt != null) "$ranges av $total" else ranges
}
