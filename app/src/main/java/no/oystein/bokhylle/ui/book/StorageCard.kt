package no.oystein.bokhylle.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.Page
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 * 1024 -> "%.0f kB".format(Locale("nb", "NO"), bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale("nb", "NO"), bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(Locale("nb", "NO"), bytes / 1024.0 / 1024.0 / 1024.0)
}

/** Hvor mye plass boka bruker, og valgene for å frigjøre plass når teksten er kontrollert. */
@Composable
fun StorageCard(book: Book, pages: List<Page>) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(-1L) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(book.id, pages.size, book.ferdigKontrollert, book.bilderSlettet) {
        while (true) {
            size = withContext(Dispatchers.IO) { app.files.bookSize(book.id) }
            delay(5000) // komprimeringen går i bakgrunnen
        }
    }
    val busy = pages.any { it.status == Page.STATUS_NY }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lagring", style = MaterialTheme.typography.titleMedium)
            Text(if (size < 0) "Beregner …" else "Boka bruker ${formatBytes(size)} på telefonen.", style = MaterialTheme.typography.bodyMedium)
            when {
                book.bilderSlettet -> Text("Sidebildene er slettet; bare teksten og figurene er beholdt.", style = MaterialTheme.typography.bodySmall)
                book.ferdigKontrollert -> Text("Ferdig kontrollert: sidebildene er skalert ned og originalene slettet.", style = MaterialTheme.typography.bodySmall)
                else -> Text(
                    "Sidebildene lagres i full oppløsning mens boka analyseres og kontrolleres.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!book.ferdigKontrollert) {
                OutlinedButton(onClick = { confirm = "ferdig" }, enabled = !busy && pages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Merk som ferdig kontrollert")
                }
            }
            if (!book.bilderSlettet) {
                OutlinedButton(onClick = { confirm = "slett" }, enabled = !busy && pages.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Slett originalbilder (behold figurer)")
                }
            }
        }
    }

    when (confirm) {
        "ferdig" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Ferdig kontrollert?") },
            text = {
                Text(
                    "Sidebildene skaleres ned til ca. 1600 px (nok til «Vis original»), og bildene fra før beskjæring slettes. " +
                        "Etterpå kan hjørnene ikke justeres lenger.",
                )
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.markFinished(book.id) }; confirm = null }) { Text("Ja, spar plass") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } },
        )
        "slett" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Slette originalbildene?") },
            text = {
                Text(
                    "Alle sidebildene slettes. Teksten, rettelsene og figurene beholdes, men «Vis original», " +
                        "ny beskjæring og ny analyse med Claude blir ikke mulig for denne boka.",
                )
            },
            confirmButton = { TextButton(onClick = { scope.launch { app.repo.deleteImages(book.id) }; confirm = null }) { Text("Slett bildene") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Avbryt") } },
        )
    }
}
