package no.oystein.bokhylle.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.BookSummary
import no.oystein.bokhylle.data.fremdrift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import no.oystein.bokhylle.ui.book.formatBytes
import no.oystein.bokhylle.ui.book.pagesDescription
import no.oystein.bokhylle.ui.theme.Accent
import java.io.File

class LibraryViewModel(private val repo: BookRepository) : ViewModel() {
    val books: StateFlow<List<BookSummary>?> =
        repo.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Lagringsbruk per bok (byte), beregnet på nytt når biblioteket endres. */
    val sizes: StateFlow<Map<Long, Long>> = repo.books
        .map { list -> list.associate { it.book.id to repo.files.bookSize(it.book.id) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun deleteImages(bookId: Long) = viewModelScope.launch { repo.deleteImages(bookId) }

    /** Sidetallene som er lagt inn, per bok. */
    val numbers: StateFlow<Map<Long, List<String?>>> = repo.db.pages().observeNumbers()
        .map { rows -> rows.groupBy({ it.bookId }, { it.oppdagetSidetall ?: it.utledetSidetall }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun delete(bookId: Long) = viewModelScope.launch { repo.deleteBook(bookId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    onNewBook: () -> Unit,
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel { LibraryViewModel(context.app.repo) }
    val books by vm.books.collectAsState()
    val sizes by vm.sizes.collectAsState()
    val numbers by vm.numbers.collectAsState()
    var toDelete by remember { mutableStateOf<BookSummary?>(null) }
    var toStrip by remember { mutableStateOf<BookSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bokhylle") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "Søk") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Innstillinger") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewBook,
                modifier = Modifier.semantics { contentDescription = "Ny bok" },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Ny bok") },
            )
        },
    ) { padding ->
        val list = books
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ingen bøker ennå.\nTa bilder av sidene med kameraet, og trykk «Ny bok» for å lage en e-bok av dem.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(list, key = { it.book.id }) { summary ->
                    BookCard(
                        summary, sizes[summary.book.id], numbers[summary.book.id].orEmpty(),
                        onClick = { onOpenBook(summary.book.id) },
                        onDelete = { toDelete = summary },
                        onDeleteImages = { toStrip = summary },
                    )
                }
            }
        }
    }

    toStrip?.let { summary ->
        AlertDialog(
            onDismissRequest = { toStrip = null },
            title = { Text("Slette originalbildene i «${summary.book.tittel}»?") },
            text = { Text("Teksten, rettelsene og figurene beholdes. «Vis original», ny beskjæring og ny analyse blir ikke mulig.") },
            confirmButton = { TextButton(onClick = { vm.deleteImages(summary.book.id); toStrip = null }) { Text("Slett bildene") } },
            dismissButton = { TextButton(onClick = { toStrip = null }) { Text("Avbryt") } },
        )
    }

    toDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Slette «${summary.book.tittel}»?") },
            text = { Text("Boka og alle ${summary.antallSider} sidebilder slettes fra telefonen. Dette kan ikke angres.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(summary.book.id); toDelete = null }) { Text("Slett") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Avbryt") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    summary: BookSummary,
    size: Long?,
    numbers: List<String?>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onDeleteImages: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .semantics { contentDescription = summary.book.tittel },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = summary.omslag?.takeIf { File(it).exists() }
            if (cover != null) {
                AsyncImage(
                    model = File(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    summary.book.tittel,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (summary.flaggede > 0) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Accent.flag).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Flag, contentDescription = "Flaggede sider", modifier = Modifier.size(14.dp))
                    Text(" ${summary.flaggede}", style = MaterialTheme.typography.labelSmall)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!summary.book.bilderSlettet) {
                    DropdownMenuItem(text = { Text("Slett originalbilder") }, onClick = { menu = false; onDeleteImages() })
                }
                DropdownMenuItem(text = { Text("Slett bok") }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(summary.book.tittel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (summary.book.forfatter.isNotBlank()) {
            Text(summary.book.forfatter, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val book = summary.book
        if (!book.ferdigKontrollert) {
            Text("Under arbeid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            pagesDescription(book, numbers)?.let {
                Text("Sider: $it", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (summary.ventende > 0) {
            val done = summary.antallSider - summary.ventende
            LinearProgressIndicator(progress = { done.toFloat() / summary.antallSider }, modifier = Modifier.fillMaxWidth())
            Text("Behandler $done av ${summary.antallSider}", style = MaterialTheme.typography.labelSmall)
        } else if (summary.antallSider > 0) {
            LinearProgressIndicator(progress = { summary.fremdrift }, modifier = Modifier.fillMaxWidth())
            Text(
                (if (summary.lestTil == 0) "${summary.antallSider} sider" else "${(summary.fremdrift * 100).toInt()} % lest") +
                    (size?.let { " · " + formatBytes(it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!book.ferdigKontrollert) {
            TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                Text("Fortsett å legge til sider", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
