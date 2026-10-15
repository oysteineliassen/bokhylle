package no.oystein.bokhylle.ui.reader

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import no.oystein.bokhylle.app
import no.oystein.bokhylle.claude.ClaudeDraw
import no.oystein.bokhylle.claude.ClaudeModel
import no.oystein.bokhylle.claude.DrawWorker
import no.oystein.bokhylle.claude.formatUsd
import no.oystein.bokhylle.data.PageImages
import java.util.UUID
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import no.oystein.bokhylle.core.BlockType
import no.oystein.bokhylle.core.Box as NormBox
import no.oystein.bokhylle.core.ManualRegion
import no.oystein.bokhylle.data.Page
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Bilde som kan zoomes med to fingre og flyttes. */
@Composable
fun ZoomableImage(path: String, description: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        },
    ) {
        AsyncImage(
            model = File(path),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
fun FigureDialog(path: String, description: String?, pageId: Long?, modelId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val drawing = PageImages.isDrawing(path)
    var work by remember { mutableStateOf<UUID?>(null) }
    var info by remember { mutableStateOf<WorkInfo?>(null) }
    work?.let { id -> LaunchedEffect(id) { WorkManager.getInstance(context).getWorkInfoByIdFlow(id).collect { info = it } } }
    val proposal = info?.takeIf { it.state == WorkInfo.State.SUCCEEDED }?.outputData?.getString(DrawWorker.KEY_DRAWING)

    fun discardProposal() {
        info?.outputData?.let { out ->
            out.getString(DrawWorker.KEY_DRAWING)?.let { File(it).delete() }
            out.getString(DrawWorker.KEY_SVG)?.let { File(it).delete() }
        }
    }

    Dialog(onDismissRequest = { discardProposal(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (proposal != null) {
                // Valget øverst (knappene nederst kunne havne bak navigasjonslinjen), så foto og tegning over hverandre.
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { if (pageId != null) scope.launch { app.repo.useDrawing(pageId, path, proposal); onDismiss() } },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Bruk tegningen" },
                        ) { Text("Bruk tegningen") }
                        OutlinedButton(onClick = { discardProposal(); onDismiss() }, modifier = Modifier.weight(1f)) {
                            Text("Behold fotoet", color = Color.White)
                        }
                    }
                    Text("Tegnet på nytt", color = Color.White)
                    AsyncImage(model = File(proposal), contentDescription = "Tegning", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))
                    Text("Foto", color = Color.White)
                    AsyncImage(model = File(path), contentDescription = "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                // Øverst: lukk og «Tegn på nytt»/«Bruk fotoet igjen». Så bildet, og teksten rett under.
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Lukk", tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        val state = info?.state
                        if (pageId != null) when {
                            drawing -> OutlinedButton(
                                onClick = { scope.launch { app.repo.usePhoto(pageId, path); onDismiss() } },
                                modifier = Modifier.semantics { contentDescription = "Bruk fotoet igjen" },
                            ) {
                                Text("Bruk fotoet igjen", color = Color.White)
                            }
                            state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Text(if (state == WorkInfo.State.ENQUEUED) "  Venter på nett …" else "  Tegner …", color = Color.White)
                            }
                            state == WorkInfo.State.SUCCEEDED -> Unit
                            app.claude.hasKey -> OutlinedButton(
                                onClick = { work = DrawWorker.one(context, pageId, path) },
                                modifier = Modifier.semantics { contentDescription = "Tegn på nytt" },
                            ) {
                                Text("Tegn på nytt (ca. ${formatUsd(ClaudeDraw.estimate(ClaudeModel.of(modelId), 1))})", color = Color.White)
                            }
                        }
                    }
                    val aspect = remember(path) {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, o)
                        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1.4f
                    }
                    ZoomableImage(path, description, Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 3f)))
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (description != null) Text(description, color = Color.White)
                        val state = info?.state
                        when {
                            pageId == null || drawing -> Unit
                            state == WorkInfo.State.RUNNING -> Text("Claude tegner figuren. Det tar gjerne et minutt.", color = Color.White)
                            state == WorkInfo.State.SUCCEEDED -> Text(
                                "Claude mener dette er et fotografi eller en detaljert illustrasjon, så fotoet beholdes.",
                                color = Color.White,
                            )
                            state == WorkInfo.State.FAILED -> Text(
                                "Det gikk ikke: ${info?.outputData?.getString(DrawWorker.KEY_ERROR) ?: "ukjent feil"}",
                                color = Color.White,
                            )
                            !app.claude.hasKey -> Text("Legg inn en API-nøkkel i Innstillinger for å kunne tegne figuren på nytt.", color = Color.White)
                        }
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

/**
 * Sidebildet for sammenligning med teksten. I markeringsmodus drar man et rektangel rundt et område og
 * sier om det er en figur eller en faktaboks.
 */
@Composable
fun OriginalDialog(page: Page, onDismiss: () -> Unit, onMark: (ManualRegion) -> Unit, onClear: () -> Unit) {
    var marking by remember { mutableStateOf(false) }
    var rect by remember { mutableStateOf<NormBox?>(null) }
    val regions = ManualRegion.decode(page.manuelleOmrader)
    val exists = remember(page.bildeSti) { File(page.bildeSti).exists() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Lukk", tint = Color.White) }
                    Text(if (marking) "Dra et rektangel rundt området" else "Original", color = Color.White, modifier = Modifier.weight(1f))
                    if (!exists) {
                        Unit
                    } else if (!marking) {
                        OutlinedButton(
                            onClick = { marking = true; rect = null },
                            modifier = Modifier.semantics { contentDescription = "Marker område" },
                        ) { Text("Marker område", color = Color.White) }
                    } else {
                        OutlinedButton(onClick = { marking = false; rect = null }) { Text("Avbryt", color = Color.White) }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (!exists) {
                        Text(
                            "Sidebildet er slettet for å spare plass.", color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else if (!marking) {
                        ZoomableImage(page.bildeSti, "Sidebildet", Modifier.fillMaxSize())
                    } else {
                        MarkingCanvas(page.bildeSti, regions, rect) { rect = it }
                    }
                }
                if (!marking && regions.isNotEmpty()) {
                    TextButton(onClick = onClear, modifier = Modifier.padding(8.dp)) {
                        Text("Fjern ${regions.size} markering(er) på denne siden", color = Color.White)
                    }
                }
            }
        }
    }
    rect?.takeIf { marking && it.width > 0.02f && it.height > 0.02f }?.let { r ->
        AlertDialog(
            onDismissRequest = { rect = null },
            title = { Text("Hva er dette området?") },
            text = { Text("Teksten i området tas ut av brødteksten.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onMark(ManualRegion(BlockType.FIGURE, r)); rect = null; marking = false }) { Text("En figur") }
                    TextButton(onClick = { onMark(ManualRegion(BlockType.BOX, r)); rect = null; marking = false }) { Text("En faktaboks") }
                }
            },
            dismissButton = { TextButton(onClick = { rect = null }) { Text("Avbryt") } },
        )
    }
}

@Composable
private fun MarkingCanvas(path: String, regions: List<ManualRegion>, rect: NormBox?, onRect: (NormBox?) -> Unit) {
    val size = remember(path) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        o.outWidth to o.outHeight
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val scale = if (size.first > 0) min(boxW / size.first, boxH / size.second) else 1f
        val imgW = size.first * scale
        val imgH = size.second * scale
        val ox = (boxW - imgW) / 2
        val oy = (boxH - imgH) / 2
        fun norm(o: Offset) = Offset(((o.x - ox) / imgW).coerceIn(0f, 1f), ((o.y - oy) / imgH).coerceIn(0f, 1f))
        var start by remember { mutableStateOf<Offset?>(null) }
        AsyncImage(model = File(path), contentDescription = "Sidebildet", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize().pointerInput(imgW, imgH) {
                detectDragGestures(
                    onDragStart = { start = norm(it); onRect(null) },
                    onDrag = { change, _ ->
                        val s = start ?: return@detectDragGestures
                        val e = norm(change.position)
                        onRect(NormBox(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y)))
                    },
                    onDragEnd = { start = null },
                )
            },
        ) {
            fun draw(b: NormBox, color: Color) = drawRect(
                color, topLeft = Offset(ox + b.left * imgW, oy + b.top * imgH),
                size = Size(b.width * imgW, b.height * imgH), style = Stroke(width = 3.dp.toPx()),
            )
            regions.forEach { draw(it.box, if (it.type == BlockType.FIGURE) Color(0xFF64B5F6) else Color(0xFF81C784)) }
            rect?.let { draw(it, Color(0xFFFFB74D)) }
        }
    }
}

/** Retting av OCR-feil. Teksten er i et enkelt format der figurer og faktabokser står i hakeparenteser. */
@Composable
fun EditDialog(initial: String, edited: Boolean, onSave: (String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
                Text("Rediger tekst", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Avsnitt skilles med blank linje. «# » = overskrift, [boks: …] … [/boks] = faktaboks, [figur: …], [^1] = fotnote.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (edited) TextButton(onClick = onReset) { Text("Tilbakestill") }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Avbryt") }
                    Button(onClick = { onSave(text) }) { Text("Lagre") }
                }
            }
        }
    }
}
