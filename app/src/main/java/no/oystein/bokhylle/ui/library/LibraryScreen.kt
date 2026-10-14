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
import no.oystein.bokhylle.ui.theme.Accent
import java.io.File

class LibraryViewModel(private val repo: BookRepository) : ViewModel() {
    val books: StateFlow<List<BookSummary>?> =
        repo.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
    var toDelete by remember { mutableStateOf<BookSummary?>(null) }

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
                    BookCard(summary, onClick = { onOpenBook(summary.book.id) }, onDelete = { toDelete = summary })
                }
            }
        }
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
private fun BookCard(summary: BookSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = summary.omslag
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
                DropdownMenuItem(text = { Text("Slett bok") }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(summary.book.tittel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (summary.book.forfatter.isNotBlank()) {
            Text(summary.book.forfatter, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        if (summary.ventende > 0) {
            val done = summary.antallSider - summary.ventende
            LinearProgressIndicator(progress = { done.toFloat() / summary.antallSider }, modifier = Modifier.fillMaxWidth())
            Text("Behandler $done av ${summary.antallSider}", style = MaterialTheme.typography.labelSmall)
        } else if (summary.antallSider > 0) {
            LinearProgressIndicator(progress = { summary.fremdrift }, modifier = Modifier.fillMaxWidth())
            Text(
                if (summary.lestTil == 0) "${summary.antallSider} sider" else "${(summary.fremdrift * 100).toInt()} % lest",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
