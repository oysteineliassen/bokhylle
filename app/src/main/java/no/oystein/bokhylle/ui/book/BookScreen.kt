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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.BookRepository
import no.oystein.bokhylle.data.Page

class BookViewModel(val bookId: Long, private val repo: BookRepository) : ViewModel() {
    val book: StateFlow<Book?> = repo.book(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val pages: StateFlow<List<Page>> = repo.pages(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(done: () -> Unit) = viewModelScope.launch { repo.deleteBook(bookId); done() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: BookViewModel = viewModel(key = "bok-$bookId") { BookViewModel(bookId, context.app.repo) }
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
            Text("${pages.size} sider", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
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
