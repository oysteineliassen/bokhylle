package no.oystein.bokhylle.ui.book

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.backup.FigureExchange
import no.oystein.bokhylle.data.Book
import no.oystein.bokhylle.data.Page
import java.io.File

/**
 * Figurer tegnet på nytt i en chat med Claude i stedet for via API-et: eksporter figurene, send filen i chatten,
 * og importer filen med tegningene som kommer tilbake.
 */
@Composable
fun FiguresCard(book: Book, pages: List<Page>) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val count = remember(pages) { FigureExchange.count(pages) }
    // Bøker uten figurer trenger ikke kortet.
    val hasFigures = remember(pages) {
        pages.any { p -> no.oystein.bokhylle.core.PageLayout.decode(p.layoutJson)?.blocks.orEmpty().any { it.type == no.oystein.bokhylle.core.BlockType.FIGURE } }
    }
    if (!hasFigures) return
    var busy by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Legger inn tegningene …"
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { FigureExchange.importDrawings(app, book.id, it) }
                }.fold(
                    onSuccess = { r ->
                        if (r == null) "Kunne ikke åpne filen"
                        else "${r.added} tegninger lagt inn" + (if (r.skipped > 0) ", ${r.skipped} passet ikke" else "")
                    },
                    onFailure = { it.message ?: "Kunne ikke lese filen" },
                )
            }
            busy = null
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // .zip-endelse, så filen kan legges ved i en chat (ukjente endelser avvises ofte der).
    val fileName = remember(book.tittel) {
        val name = book.tittel.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { "bok" }
        "$name ${FigureExchange.FIGURES_EXTENSION}.zip"
    }
    suspend fun exportFile(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "del").apply { mkdirs() }
            File(dir, fileName).also { f -> f.outputStream().use { FigureExchange.export(app, book.id, it) } }
        }.getOrNull()
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Lagrer figurfilen …"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { FigureExchange.export(app, book.id, it) } != null
                }.getOrDefault(false)
            }
            busy = null
            Toast.makeText(context, if (ok) "Figurfilen er lagret" else "Kunne ikke lagre figurfilen", Toast.LENGTH_LONG).show()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tegne figurer i en chat", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count > 0) "$count figurer er ikke tegnet på nytt. Eksporter dem, send filen i en chat med Claude, og importer " +
                    "filen med tegningene du får tilbake. Bruker ikke API-kreditt."
                else "Ingen figurer igjen å tegne. Har du fått en fil med tegninger tilbake, importer den her.",
                style = MaterialTheme.typography.bodySmall,
            )
            busy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (count > 0) {
                OutlinedButton(
                    enabled = busy == null,
                    onClick = {
                        busy = "Lager figurfilen …"
                        scope.launch {
                            val file = exportFile()
                            busy = null
                            if (file == null) {
                                Toast.makeText(context, "Kunne ikke lage figurfilen", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.filer", file)
                            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Send figurene til tegning"))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Eksporter figurer til tegning" },
                ) { Text("Eksporter figurer til tegning ($count)") }
                OutlinedButton(
                    enabled = busy == null,
                    onClick = { saveAs.launch(fileName) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Lagre figurfilen på telefonen") }
            }
            OutlinedButton(
                enabled = busy == null,
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Importer tegninger") }
        }
    }
}
