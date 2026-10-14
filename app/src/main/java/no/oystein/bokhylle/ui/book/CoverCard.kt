package no.oystein.bokhylle.ui.book

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.Page
import java.io.File

/** Omslaget i biblioteket: eget bilde av forsiden, en valgt side, eller første side. */
@Composable
fun CoverCard(book: Book, pages: List<Page>) {
    val context = LocalContext.current
    val repo = context.app.repo
    val scope = rememberCoroutineScope()
    fun use(uri: android.net.Uri?) {
        if (uri != null) scope.launch { repo.setCoverImage(book.id, uri) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { use(it) }
    // Dokumentskanneren finner kantene og retter opp forsiden, så den blir et rett, beskåret omslag.
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            use(GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri)
        }
    }

    val pageCover = (pages.firstOrNull { it.id == book.coverPageId } ?: pages.minByOrNull { it.sorteringsIndeks })?.bildeSti
    val shown = (book.omslagBilde ?: pageCover)?.takeIf { File(it).exists() }

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.width(84.dp).height(126.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (shown != null) {
                    AsyncImage(model = File(shown), contentDescription = "Omslag", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                } else {
                    Text(book.tittel, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Omslag", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (book.omslagBilde != null) "Eget bilde av forsiden." else "Viser en side fra boka. Ta et bilde av forsiden for et ekte omslag.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { startScanner(context, pageLimit = 1) { scanner.launch(it) } },
                    modifier = Modifier.semantics { contentDescription = "Ta bilde av omslaget" },
                ) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                    Text("  Ta bilde av omslaget")
                }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text("  Velg fra galleriet")
                }
                if (book.omslagBilde != null) {
                    TextButton(onClick = { scope.launch { repo.removeCoverImage(book.id) } }) { Text("Fjern omslagsbildet") }
                }
            }
        }
    }
}
