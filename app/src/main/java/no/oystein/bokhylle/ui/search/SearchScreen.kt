package no.oystein.bokhylle.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.SearchHit

/** Søk i titler og forfattere, og i fulltekst på tvers av alle bøker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit, onOpenPage: (bookId: Long, pageId: Long) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    var query by rememberSaveable { mutableStateOf("") }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(250)
        val q = query.trim().lowercase()
        if (q.length < 2) { books = emptyList(); hits = emptyList(); return@LaunchedEffect }
        books = app.db.books().all().filter { q in it.tittel.lowercase() || q in it.forfatter.lowercase() }
        val escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        hits = app.db.pages().search(escaped)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
                title = {
                    TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Søk i titler og tekst") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (books.isNotEmpty()) {
                item { Text("Bøker", style = MaterialTheme.typography.titleSmall) }
                items(books, key = { "b${it.id}" }) { b ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenBook(b.id) }.padding(vertical = 6.dp)) {
                        Text(b.tittel, style = MaterialTheme.typography.titleMedium)
                        if (b.forfatter.isNotBlank()) Text(b.forfatter, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { HorizontalDivider() }
            }
            if (hits.isNotEmpty()) {
                item { Text("I teksten (${hits.size}${if (hits.size >= 200) "+" else ""} treff)", style = MaterialTheme.typography.titleSmall) }
                items(hits, key = { "p${it.pageId}" }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenPage(h.bookId, h.pageId) }.padding(vertical = 6.dp)) {
                        Text("${h.tittel}${h.oppdagetSidetall?.let { ", side $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
                        Text(snippet(h.tekst, query.trim()), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (query.trim().length >= 2 && books.isEmpty() && hits.isEmpty()) {
                item { Text("Ingen treff.", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Utdrag rundt treffet, med søkeordet uthevet. */
private fun snippet(text: String, query: String) = buildAnnotatedString {
    val clean = text.replace(PageLayout.FOOTNOTE_REF, "").replace(Regex("\\s+"), " ")
    val i = clean.lowercase().indexOf(query.lowercase())
    if (i < 0) { append(clean.take(160)); return@buildAnnotatedString }
    val start = (i - 70).coerceAtLeast(0)
    val end = (i + query.length + 90).coerceAtMost(clean.length)
    if (start > 0) append("…")
    append(clean.substring(start, i))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x55FFD54F))) { append(clean.substring(i, i + query.length)) }
    append(clean.substring(i + query.length, end))
    if (end < clean.length) append("…")
}
