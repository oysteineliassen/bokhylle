package no.oystein.bokhylle.ui.backup

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import no.oystein.bokhylle.backup.BookPackage
import no.oystein.bokhylle.backup.ExportWorker
import no.oystein.bokhylle.data.Book
import java.io.File
import java.util.UUID

private enum class ExportKind(val label: String, val description: String) {
    FULL("Bokhylle-fil med originalbilder", "Alt, så boka kan kontrolleres og analyseres på nytt. Stor fil."),
    TEXT("Bokhylle-fil, bare tekst og figurer", "Mindre fil; nok til å lese og søke."),
    EPUB("EPUB", "For andre lesere (tekst, overskrifter, faktabokser og figurer)."),
}

/** Eksport av én bok: velg format, så «Lagre som …» (velg sted) eller «Del». Eksporten går i bakgrunnen. */
@Composable
fun ExportDialog(book: Book, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(ExportKind.FULL) }
    var work by remember { mutableStateOf<UUID?>(null) }
    var shareFile by remember { mutableStateOf<File?>(null) }
    val safeName = book.tittel.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifEmpty { "bok" }
    val ext = if (kind == ExportKind.EPUB) "epub" else BookPackage.EXTENSION
    val mime = if (kind == ExportKind.EPUB) "application/epub+zip" else "application/octet-stream"

    fun start(target: Uri) {
        val type = if (kind == ExportKind.EPUB) ExportWorker.KIND_EPUB else ExportWorker.KIND_BOOK
        work = ExportWorker.enqueue(context, type, book.id, target, withImages = kind == ExportKind.FULL)
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri -> if (uri != null) start(uri) }

    val id = work
    if (id != null) {
        var info by remember { mutableStateOf<WorkInfo?>(null) }
        LaunchedEffect(id) { WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { info = it } }
        val state = info?.state
        LaunchedEffect(state) {
            when (state) {
                WorkInfo.State.SUCCEEDED -> {
                    val f = shareFile
                    if (f != null) {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.filer", f)
                        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Del «${book.tittel}»"))
                    } else {
                        Toast.makeText(context, "Eksportert", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
                WorkInfo.State.FAILED -> {
                    Toast.makeText(context, "Eksporten feilet: ${info?.outputData?.getString(ExportWorker.KEY_ERROR) ?: ""}", Toast.LENGTH_LONG).show()
                    onDismiss()
                }
                else -> Unit
            }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Eksporterer …") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fortsetter i bakgrunnen om du går ut av appen.")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Skjul") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eksporter «${book.tittel}»") },
        text = {
            Column {
                ExportKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = kind == k, onClick = { kind = k }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Column {
                            Text(k.label, style = MaterialTheme.typography.bodyLarge)
                            Text(k.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (kind == ExportKind.EPUB) GrayFiguresOption()
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val dir = File(context.cacheDir, "del").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val f = File(dir, "$safeName.$ext")
                    shareFile = f
                    start(Uri.fromFile(f))
                }) { Text("Del") }
                TextButton(onClick = { saveAs.launch("$safeName.$ext") }) { Text("Lagre som …") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
