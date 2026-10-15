package no.oystein.bokhylle.ui.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.PageLayout
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.PageImages
import no.oystein.bokhylle.data.sidetallVisning
import no.oystein.bokhylle.ui.reader.ZoomableImage
import java.io.File

/** En figur som både har et foto og en tegning: [current] er det leseren viser nå. */
data class FigurePair(val page: Page, val photo: String, val drawing: String, val drawingInUse: Boolean, val description: String?) {
    val current get() = if (drawingInUse) drawing else photo
}

fun figurePairs(pages: List<Page>): List<FigurePair> = pages.filter { it.status == Page.STATUS_FERDIG }.flatMap { p ->
    PageLayout.decode(p.layoutJson)?.blocks.orEmpty().mapNotNull { b ->
        val image = b.image ?: return@mapNotNull null
        if (b.type != BlockType.FIGURE) return@mapNotNull null
        val pair = when {
            PageImages.isDrawing(image) -> b.photo?.let { FigurePair(p, it, image, true, b.description) }
            else -> b.drawing?.let { FigurePair(p, image, it, false, b.description) }
        }
        pair?.takeIf { File(it.photo).exists() && File(it.drawing).exists() }
    }
}

/** Foto og tegning side om side for hver tegnede figur, så man kan dobbeltsjekke tegningene og velge per figur. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FigureCompareScreen(bookId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val pages by remember(bookId) { app.db.pages().observe(bookId) }.collectAsState<List<Page>, List<Page>?>(initial = null)
    val pairs = remember(pages) { figurePairs(pages.orEmpty()) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Se over tegningene") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    if (pages != null && pairs.isEmpty()) "Ingen figurer i boka er tegnet på nytt ennå."
                    else "Fotoet til venstre, tegningen til høyre. Trykk på et bilde for å forstørre det. " +
                        "Det som er merket «I bruk», vises i leseren og på Kindle.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(pairs, key = { it.page.id.toString() + it.photo }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Side ${f.page.sidetallVisning ?: "?"}", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Thumb(f.photo, "Foto", !f.drawingInUse, Modifier.weight(1f)) { zoom = f.photo }
                            Thumb(f.drawing, "Tegning", f.drawingInUse, Modifier.weight(1f)) { zoom = f.drawing }
                        }
                        f.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (f.drawingInUse) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { busy = true; scope.launch { app.repo.usePhoto(f.page.id, f.drawing); busy = false } },
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Bruk fotoet" },
                            ) { Text("Bruk fotoet") }
                        } else {
                            Button(
                                enabled = !busy,
                                onClick = { busy = true; scope.launch { app.repo.useParkedDrawing(f.page.id, f.photo); busy = false } },
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Bruk tegningen" },
                            ) { Text("Bruk tegningen") }
                        }
                    }
                }
            }
        }
    }

    zoom?.let { path ->
        Dialog(onDismissRequest = { zoom = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
                ZoomableImage(path, null, Modifier.fillMaxSize())
                IconButton(onClick = { zoom = null }, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.Default.Close, "Lukk", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun Thumb(path: String, label: String, inUse: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (inUse) "$label · I bruk" else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AsyncImage(
            model = File(path),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .border(if (inUse) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .background(Color.White, shape)
                .padding(4.dp)
                .clickable(onClick = onClick),
        )
    }
}
