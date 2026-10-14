package no.oystein.bokhylle.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.LayoutBlock
import no.oystein.bokhylle.core.LayoutMarkup
import no.oystein.bokhylle.core.ManualRegion
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.Page

class ReaderViewModel(val bookId: Long, private val app: BokhylleApp) : ViewModel() {
    private val repo = app.repo
    val book: StateFlow<Book?> = repo.book(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pages: StateFlow<List<Page>?> = repo.pages(bookId)
        .map { list -> list.filter { it.status == Page.STATUS_FERDIG } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val prefs = app.readerPrefs
    val hasClaude: Boolean get() = app.claude.hasKey

    /** Oppsettet uten brukerens tekstrettelser. */
    fun baseLayout(p: Page): PageLayout = PageLayout.decode(p.layoutJson) ?: PageLayout(
        p.ocrTekst.split(Regex("\\n\\s*\\n")).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }
            .map { LayoutBlock(BlockType.PARAGRAPH, it) },
    )

    /** Oppsettet leseren viser: med rettelsene hvis brukeren har redigert teksten. */
    fun layoutOf(p: Page): PageLayout {
        val base = baseLayout(p)
        return p.redigertTekst?.let { LayoutMarkup.parse(it, base) } ?: base
    }

    fun editableText(p: Page): String = p.redigertTekst ?: LayoutMarkup.toMarkup(baseLayout(p))

    private var saveJob: Job? = null
    fun saveLastRead(pageId: Long) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            repo.setLastRead(bookId, pageId)
        }
    }

    fun saveEdit(page: Page, text: String?) = viewModelScope.launch {
        val unchanged = text != null && text.trim() == LayoutMarkup.toMarkup(baseLayout(page)).trim()
        app.db.pages().setEditedText(page.id, if (unchanged) null else text)
        app.processor.rebuild(bookId)
    }

    fun addRegion(page: Page, region: ManualRegion) = viewModelScope.launch {
        val regions = ManualRegion.decode(page.manuelleOmrader) + region
        app.db.pages().setRegions(page.id, ManualRegion.encode(regions))
        app.processor.rebuild(bookId)
    }

    fun clearRegions(page: Page) = viewModelScope.launch {
        app.db.pages().setRegions(page.id, null)
        app.processor.rebuild(bookId)
    }

    fun runClaude(page: Page) = app.claude.runPage(bookId, page.id)

    /** Fotnoteteksten: på samme side, ellers i en notedel senere (eller tidligere) i boka. */
    fun findFootnote(pageIndex: Int, number: String): String? {
        val list = pages.value ?: return null
        val order = (pageIndex until list.size) + (pageIndex - 1 downTo 0)
        for (i in order) {
            layoutOf(list[i]).blocks.firstOrNull { it.type == BlockType.FOOTNOTE && it.number == number }?.let { return it.text }
        }
        return null
    }
}
