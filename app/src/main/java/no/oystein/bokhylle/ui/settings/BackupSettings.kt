package no.oystein.bokhylle.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.backup.BackupManager
import no.oystein.bokhylle.backup.BackupWorker
import no.oystein.bokhylle.backup.ExportWorker
import no.oystein.bokhylle.backup.RestoreWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Sikkerhetskopi: automatisk til en valgt mappe, manuelt til fil, gjenoppretting og angre. */
@Composable
fun BackupSettings(onImport: (path: String, undo: Boolean) -> Unit) {
    val context = LocalContext.current
    val backup = context.app.backup
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(backup.folder) }
    var frequency by remember { mutableIntStateOf(backup.frequencyDays) }
    var originals by remember { mutableStateOf(backup.withOriginals) }
    val dateFormat = remember { SimpleDateFormat("d. MMM yyyy 'kl.' HH:mm", Locale("nb", "NO")) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Varig tilgang, så kopien kan skrives i bakgrunnen senere.
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            backup.folder = uri
            folder = uri
            if (frequency == 0) { frequency = 7; backup.frequencyDays = 7 }
            BackupWorker.schedule(context)
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            ExportWorker.enqueue(context, ExportWorker.KIND_BACKUP, -1, uri, withImages = true)
            Toast.makeText(context, "Sikkerhetskopien lages i bakgrunnen", Toast.LENGTH_SHORT).show()
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, RestoreWorker.INCOMING).apply { mkdirs() }
                    val f = File(dir, "${UUID.randomUUID()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f.path
                }.getOrNull()
            }
            if (path != null) onImport(path, false) else Toast.makeText(context, "Kunne ikke lese filen", Toast.LENGTH_LONG).show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sikkerhetskopi", style = MaterialTheme.typography.titleLarge)
        Text(
            "Hele biblioteket lagres som én .bokhylle-backup-fil, med bøker, rettelser, bilder og leserinnstillinger. " +
                "API-nøkkelen blir aldri med.",
            style = MaterialTheme.typography.bodySmall,
        )
        val folderName = folder?.let { DocumentFile.fromTreeUri(context, it)?.name }
        Text(if (folderName != null) "Mappe: $folderName" else "Ingen mappe valgt for automatisk kopi.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (folder == null) "Velg mappe" else "Bytt mappe") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Av", 1 to "Daglig", 7 to "Ukentlig").forEach { (days, label) ->
                FilterChip(
                    selected = frequency == days,
                    enabled = folder != null || days == 0,
                    onClick = { frequency = days; backup.frequencyDays = days; BackupWorker.schedule(context) },
                    label = { Text(label) },
                )
            }
        }
        Text("Kopien tas når telefonen lader og er på wifi. De ${BackupManager.KEEP} siste beholdes.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = originals, onCheckedChange = { originals = it; backup.withOriginals = it })
            Text("Ta med sidebildene (større fil)")
        }
        val last = backup.lastSuccess
        Text(if (last > 0) "Siste vellykkede kopi: ${dateFormat.format(Date(last))}" else "Ingen automatisk kopi er tatt ennå.", style = MaterialTheme.typography.bodyMedium)
        backup.lastError?.let { Text("Siste forsøk feilet: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                BackupWorker.runNow(context)
                Toast.makeText(context, "Sikkerhetskopien tas i bakgrunnen", Toast.LENGTH_SHORT).show()
            }, enabled = folder != null) { Text("Ta kopi nå") }
            OutlinedButton(onClick = {
                saveAs.launch("Bokhylle-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.${BackupManager.EXTENSION}")
            }) { Text("Lagre som …") }
        }
        Text("Gjenoppretting", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Gjenopprett eller importer fra fil …") }
        if (backup.canUndo) {
            val at = backup.preRestoreFile.lastModified()
            OutlinedButton(onClick = { onImport(backup.preRestoreFile.path, true) }, modifier = Modifier.fillMaxWidth()) {
                Text("Angre siste gjenoppretting (kopi fra ${dateFormat.format(Date(at))})")
            }
        }
    }
}
