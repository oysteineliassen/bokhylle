package no.oystein.bokhylle.ui.book

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Tre måter å legge inn sider på. Bildene leveres til [onImages]; [preCropped] er true for dokumentskanneren,
 * som allerede har beskåret og rettet opp sidene.
 */
@Composable
fun ImportButtons(enabled: Boolean = true, onImages: (uris: List<Uri>, preCropped: Boolean) -> Unit) {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun deliver(uris: List<Uri>, preCropped: Boolean) {
        if (uris.isEmpty()) return
        // Varselet viser fremdriften mens appen er lukket.
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        onImages(uris, preCropped)
    }

    // Photo Picker: så mange som systemet tillater (ofte 100). Filer-velgeren har ingen grense.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { deliver(it, false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { deliver(it, false) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages.orEmpty()
            deliver(pages.map { it.imageUri }, true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier)
            Text("  Velg bilder")
        }
        OutlinedButton(onClick = { files.launch("image/*") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text("  Velg fra Filer (ingen grense)")
        }
        OutlinedButton(
            onClick = { startScanner(context) { scanner.launch(it) } },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(),
        ) {
            Icon(Icons.Outlined.DocumentScanner, contentDescription = null)
            Text("  Skann sider i appen")
        }
    }
}

/** @param pageLimit maks antall sider; null = ingen grense (standard i dokumentskanneren). */
internal fun startScanner(context: Context, pageLimit: Int? = null, launch: (IntentSenderRequest) -> Unit) {
    val activity = context.findActivity() ?: return
    val options = GmsDocumentScannerOptions.Builder()
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .setGalleryImportAllowed(true)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .apply { if (pageLimit != null) setPageLimit(pageLimit) }
        .build()
    GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
        .addOnSuccessListener { launch(IntentSenderRequest.Builder(it).build()) }
        .addOnFailureListener {
            Toast.makeText(context, "Dokumentskanneren er ikke tilgjengelig: ${it.message}", Toast.LENGTH_LONG).show()
        }
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
