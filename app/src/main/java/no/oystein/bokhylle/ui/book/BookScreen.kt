package no.oystein.bokhylle.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.flow.map
import no.oystein.bokhylle.BokhylleApp
import no.oystein.bokhylle.app
import no.oystein.bokhylle.importer.ImportProgress
import no.oystein.bokhylle.importer.ImportResult
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.Page

class BookViewModel(val bookId: Long, private val app: BokhylleApp) : ViewModel() {
    private val repo: BookRepository = app.repo
    val copying: StateFlow<ImportProgress?> = app.importer.progress.map { it[bookId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var lastImport by mutableStateOf<ImportResult?>(null)
        private set

    fun import(uris: List<Uri>, preCropped: Boolean) {
        app.appScope.launch { lastImport = app.importer.import(bookId, uris, preCropped) }
    }

    val book: StateFlow<Book?> = repo.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pages: StateFlow<List<Page>> = repo.pages(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(done: () -> Unit) = viewModelScope.launch { repo.deleteBook(bookId); done() }

    fun setTotal(total: Int?) = viewModelScope.launch {
        book.value?.let { repo.updateBook(it.copy(antallSiderTotalt = total?.takeIf { n -> n > 0 })) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    onReview: (Long) -> Unit = {},
    onRead: (Long) -> Unit = {},
    onSettings: () -> Unit = {},
    onCompareFigures: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: BookViewModel = viewModel(key = "bok-$bookId") { BookViewModel(bookId, context.app) }
    val copying by vm.copying.collectAsState()
    val book by vm.book.collectAsState()
    val pages by vm.pages.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.tittel ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Slett bok") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val b = book ?: return@Column
            if (b.forfatter.isNotBlank()) Text(b.forfatter, style = MaterialTheme.typography.titleMedium)
            val pending = pages.count { it.status == Page.STATUS_NY }
            val failed = pages.count { it.status == Page.STATUS_FEIL }
            Text("${pages.size} sider", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
            var editTotal by remember { mutableStateOf(false) }
            Text(
                (if (b.ferdigKontrollert) "Ferdig" else "Under arbeid") +
                    (pagesDescription(b, pages.filter { it.status == Page.STATUS_FERDIG }.map { it.oppdagetSidetall ?: it.utledetSidetall })
                        ?.let { " · sider lagt inn: $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { editTotal = true }) {
                Text(if (b.antallSiderTotalt == null) "Oppgi hvor mange sider boka har" else "Boka har ${b.antallSiderTotalt} sider (endre)")
            }
            if (editTotal) {
                var text by remember { mutableStateOf(b.antallSiderTotalt?.toString() ?: "") }
                AlertDialog(
                    onDismissRequest = { editTotal = false },
                    title = { Text("Antall sider i boka") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = text, onValueChange = { text = it.filter(Char::isDigit) }, singleLine = true,
                            label = { Text("Siste sidetall") },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.setTotal(text.toIntOrNull()); editTotal = false }) { Text("Lagre") }
                    },
                    dismissButton = { TextButton(onClick = { editTotal = false }) { Text("Avbryt") } },
                )
            }

            CoverCard(b, pages)

            copying?.let { p ->
                Text("Kopierer bilder: ${p.done} av ${p.total}")
                LinearProgressIndicator(progress = { p.done.toFloat() / p.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
            if (pending > 0) {
                val done = pages.size - pending
                Text("Leser tekst (OCR): $done av ${pages.size}")
                LinearProgressIndicator(progress = { done.toFloat() / pages.size }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Fortsetter i bakgrunnen selv om du lukker appen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (failed > 0) {
                Text("$failed bilder kunne ikke leses.", color = MaterialTheme.colorScheme.error)
            }
            vm.lastImport?.let { r ->
                if (r.duplicates > 0 || r.failed > 0) {
                    Text(
                        buildString {
                            append("${r.added} nye sider lagt til.")
                            if (r.duplicates > 0) append(" ${r.duplicates} bilder fantes fra før og ble hoppet over.")
                            if (r.failed > 0) append(" ${r.failed} kunne ikke kopieres.")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (pages.any { it.status == Page.STATUS_FERDIG }) {
                Button(onClick = { onRead(bookId) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Les" }) {
                    Text(if (b.sistLestSide == null) "Les" else "Fortsett å lese")
                }
            }
            if (pages.isNotEmpty()) {
                val flagged = pages.count { it.flagget }
                OutlinedButton(onClick = { onReview(bookId) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Kontroller rekkefølge" }) {
                    Text(if (flagged > 0) "Kontroller rekkefølge ($flagged flagget)" else "Kontroller rekkefølge")
                }
            }

            if (pages.isNotEmpty() && !b.bilderSlettet) ClaudeCard(b, pages, onSettings)
            if (pages.isNotEmpty()) FiguresCard(b, pages, onCompare = { onCompareFigures(bookId) })
            if (pages.isNotEmpty()) StorageCard(b, pages)
            if (pages.isNotEmpty()) {
                var exporting by remember { mutableStateOf(false) }
                no.oystein.bokhylle.ui.backup.SendToKindleButton(b)
                OutlinedButton(onClick = { exporting = true }, modifier = Modifier.fillMaxWidth()) { Text("Eksporter (Bokhylle-fil eller EPUB) …") }
                if (exporting) no.oystein.bokhylle.ui.backup.ExportDialog(b) { exporting = false }
            }

            Text("Legg til sider", style = MaterialTheme.typography.titleMedium)
            ImportButtons(enabled = copying == null) { uris, preCropped -> vm.import(uris, preCropped) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Slette boka?") },
            text = { Text("Boka og alle ${pages.size} sidebilder slettes fra telefonen. Dette kan ikke angres.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Slett") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } },
        )
    }
}
