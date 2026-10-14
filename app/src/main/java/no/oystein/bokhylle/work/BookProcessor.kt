package no.oystein.bokhylle.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.OrderInput
import no.oystein.bokhylle.core.OrderResult
import no.oystein.bokhylle.core.PageOrdering
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.sidetall

/** Sorterer og rydder en hel bok etter at sidene er lest inn eller endret. */
class BookProcessor(private val app: BokhylleApp) {
    private val db get() = app.db
    private val lock = Mutex()

    suspend fun rebuild(bookId: Long) = lock.withLock {
        val pages = db.pages().forBook(bookId)
        if (pages.isEmpty()) return@withLock
        val result = analyze(pages)
        val byId = pages.associateBy { it.id }
        val updated = result.order.mapIndexedNotNull { index, id ->
            val p = byId[id] ?: return@mapIndexedNotNull null
            val flags = result.flags[id].orEmpty()
            val next = p.copy(
                sorteringsIndeks = index,
                flagget = flags.isNotEmpty(),
                flaggGrunner = flags.joinToString(",") { it.name },
                utledetSidetall = result.inferred[id]?.label,
            )
            next.takeIf { it != p }
        }
        if (updated.isNotEmpty()) db.pages().updateAll(updated)
    }

    companion object {
        /** Sorteringen og flaggene for sidene slik de er nå (brukes også av kontrollskjermen for «manglende sider»). */
        fun analyze(pages: List<Page>): OrderResult = PageOrdering.order(
            pages.map { p ->
                OrderInput(
                    id = p.id,
                    batch = p.importBatch,
                    importIndex = p.importIndeks,
                    captureTime = p.fotoTidspunkt,
                    number = p.sidetall,
                    manual = p.manuellPlassering,
                    manualAfter = p.plassertEtter,
                    accepted = p.godkjent,
                )
            },
        )
    }
}
