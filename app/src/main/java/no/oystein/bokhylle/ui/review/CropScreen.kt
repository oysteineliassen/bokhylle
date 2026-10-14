package no.oystein.bokhylle.ui.review

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.Point
import no.oystein.bokhylle.core.Quad
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.image.ImageOps
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/** Viser hele originalbildet med sidens fire hjørner, som kan dras på plass. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(pageId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<Page?>(null) }
    var imageSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var quad by remember { mutableStateOf(Quad.FULL) }

    LaunchedEffect(pageId) {
        val p = context.app.repo.page(pageId) ?: return@LaunchedEffect onBack()
        page = p
        quad = Quad.decode(p.beskjaering) ?: Quad.FULL
        val path = p.originalSti ?: p.bildeSti
        imageSize = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            o.outWidth to o.outHeight
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Juster beskjæring") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") } },
            )
        },
    ) { padding ->
        val p = page
        val size = imageSize
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dra hjørnene til sidens kanter. Siden rettes opp og leses på nytt når du lagrer.", style = MaterialTheme.typography.bodyMedium)
            if (p != null && size != null && size.first > 0) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val scale = min(boxW / size.first, boxH / size.second)
                    val imgW = size.first * scale
                    val imgH = size.second * scale
                    val ox = (boxW - imgW) / 2
                    val oy = (boxH - imgH) / 2
                    fun toScreen(pt: Point) = Offset(ox + pt.x * imgW, oy + pt.y * imgH)
                    fun toImage(o: Offset) = Point(((o.x - ox) / imgW).coerceIn(0f, 1f), ((o.y - oy) / imgH).coerceIn(0f, 1f))
                    var active by remember { mutableIntStateOf(-1) }
                    val handleRadius = with(density) { 14.dp.toPx() }
                    val accent = MaterialTheme.colorScheme.primary

                    AsyncImage(
                        model = File(p.originalSti ?: p.bildeSti),
                        contentDescription = "Originalbildet",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(imgW, imgH) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val pts = quad.points.map(::toScreen)
                                    val nearest = pts.indices.minByOrNull { hypot(pts[it].x - start.x, pts[it].y - start.y) } ?: -1
                                    active = if (nearest >= 0 && hypot(pts[nearest].x - start.x, pts[nearest].y - start.y) < handleRadius * 4) nearest else -1
                                },
                                onDrag = { change, amount ->
                                    if (active < 0) return@detectDragGestures
                                    change.consume()
                                    val pts = quad.points.toMutableList()
                                    pts[active] = toImage(toScreen(pts[active]) + amount)
                                    quad = Quad(pts[0], pts[1], pts[2], pts[3])
                                },
                                onDragEnd = { active = -1 },
                            )
                        },
                    ) {
                        val pts = quad.points.map(::toScreen)
                        val path = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.15f))
                        drawPath(path, accent, style = Stroke(width = 3.dp.toPx()))
                        pts.forEachIndexed { i, pt ->
                            drawCircle(Color.White, handleRadius, pt)
                            drawCircle(accent, handleRadius * (if (i == active) 0.85f else 0.6f), pt)
                        }
                    }
                }
                val valid = quad.isConvex() && quad.area() > 0.02f
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { quad = Quad.FULL }, modifier = Modifier.weight(1f)) { Text("Hele bildet") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val detected = withContext(Dispatchers.IO) {
                                    ImageOps.decode(p.originalSti ?: p.bildeSti)?.let { bmp -> ImageOps.detectPages(bmp).also { bmp.recycle() } }
                                }?.minByOrNull { q ->
                                    // Ved oppslag: den siden som ligger nærmest dagens utsnitt.
                                    val c = quad.points; val d = q.points
                                    kotlin.math.abs(c.sumOf { it.x.toDouble() } - d.sumOf { it.x.toDouble() })
                                }
                                if (detected != null) quad = detected
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Finn kanter") }
                    Button(
                        enabled = valid,
                        onClick = { scope.launch { context.app.repo.recrop(p, quad); onBack() } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Lagre") }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
