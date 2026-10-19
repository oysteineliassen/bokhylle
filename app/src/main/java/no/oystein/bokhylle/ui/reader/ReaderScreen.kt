package no.oystein.bokhylle.ui.reader

import androidx.compose.animation.AnimatedVisibility
import no.oystein.bokhylle.core.BlockType
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.core.ReadingFlow
import no.oystein.bokhylle.data.Marking
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.sidetallVisning
import no.oystein.bokhylle.ui.book.findActivity
import no.oystein.bokhylle.core.TocLinks

/** Merket tekst i lesestrømmen: blokk og tegnområde (slutt eksklusiv). */
private data class FlowRange(val block: Int, val start: Int, val end: Int)

/** Utvalget etter et langt trykk: setningen (som kan utvides) og punktet det ble trykket på. */
private data class Selection(val block: Int, val start: Int, val end: Int, val press: Int)

private val HIGHLIGHT = Color(0x66FFD54F)
private val SELECTION = Color(0x5542A5F5)
private val RIBBON = Color(0xFFD84315)

/**
 * Finner igjen en markering (eller bokmerket) i lesestrømmen. Står teksten ikke lenger der ankeret peker
 * (siden er analysert på nytt eller redigert), letes den opp i samme blokk og så i hele boka.
 */
private fun locate(m: Marking, flow: List<ReadingFlow.Block>, pages: List<Page>): FlowRange? {
    val startPage = pages.indexOfFirst { it.id == m.sideId }
    val endPage = pages.indexOfFirst { it.id == m.sluttSideId }
    val bookmark = m.type == Marking.BOKMERKE
    if (startPage >= 0) {
        val a = ReadingFlow.Anchor(startPage, m.blokk, m.tegn)
        for ((bi, b) in flow.withIndex()) {
            val s = b.positionOf(a) ?: continue
            val e = if (bookmark) s else (if (endPage >= 0) b.positionOf(ReadingFlow.Anchor(endPage, m.sluttBlokk, m.sluttTegn)) else null) ?: (s + m.tekst.length)
            val ok = m.tekst.isEmpty() || (if (bookmark) b.text.startsWith(m.tekst, s) else b.text.regionMatches(s, m.tekst, 0, m.tekst.length))
            if (ok) return FlowRange(bi, s, e.coerceIn(s, b.text.length))
            val near = b.text.indexOf(m.tekst)
            if (near >= 0) return FlowRange(bi, near, if (bookmark) near else near + m.tekst.length)
            break
        }
    }
    if (m.tekst.isBlank()) return null
    for ((bi, b) in flow.withIndex()) {
        val i = b.text.indexOf(m.tekst)
        if (i >= 0) return FlowRange(bi, i, if (bookmark) i else i + m.tekst.length)
    }
    return null
}

/** Skjermsiden som viser posisjonen [pos] i blokk [block]. */
private fun screenOf(screens: List<Screen>, block: Int, pos: Int): Int =
    screens.indexOfFirst { it.contains(block, pos) }

/** Hovedsiden på en skjermside: siden mest av teksten kommer fra (for original, redigering og Claude). */
private fun mainPage(screen: Screen, flow: List<ReadingFlow.Block>): Int {
    if (screen.pages.size == 1) return screen.pages.first()
    val weight = mutableMapOf<Int, Int>()
    for (item in screen.items) {
        val (block, start, len) = when (item) {
            is ScreenItem.Text -> Triple(item.block, item.start, item.text.length)
            is ScreenItem.Box -> Triple(item.block, item.start, item.text.length)
            is ScreenItem.Figure -> { weight[item.page] = (weight[item.page] ?: 0) + 200; continue }
            else -> continue
        }
        for (s in flow[block].spans) {
            val overlap = minOf(s.end, start + len) - maxOf(s.start, start)
            if (overlap > 0) weight[s.page] = (weight[s.page] ?: 0) + overlap
        }
    }
    return weight.maxByOrNull { it.value }?.key ?: screen.pages.first()
}

/**
 * Leseren: boksidene flyter etter hverandre over skjermsider (bare hovedkapitler begynner på ny skjermside).
 * Bla med trykk eller sveip: høyre tredjedel = neste, venstre = forrige, midten = meny. Langt trykk på teksten
 * markerer setningen eller setter bokmerke.
 */
@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit, startPageId: Long? = null) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "les-$bookId") { ReaderViewModel(bookId, context.app) }
    val book by vm.book.collectAsState()
    val pagesOrNull by vm.pages.collectAsState()
    val settings by vm.prefs.settings.collectAsState()
    val missing by vm.missing.collectAsState()
    val markings by vm.markings.collectAsState()

    // Fullskjerm mens boka leses: statuslinjen og navigasjonslinjen skjules, og kommer fram et øyeblikk
    // ved sveip inn fra kanten. De vises igjen når leseren lukkes.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val theme = settings.theme
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var footnote by remember { mutableStateOf<Pair<Int, String>?>(null) }
    // Figuren som vises i fullskjerm: bildet, beskrivelsen og siden den står på.
    var figure by remember { mutableStateOf<Triple<String, String?, Long?>?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var showMarkings by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Selection?>(null) }
    // Sidetallet i en oppføring i innholdsfortegnelsen som er trykket på.
    var tocTarget by remember { mutableStateOf<Int?>(null) }
    var screens by remember { mutableStateOf<List<Screen>?>(null) }
    var flow by remember { mutableStateOf<List<ReadingFlow.Block>>(emptyList()) }
    // Hvor i teksten vi står (side-id og ankeret i den); brukes til å finne igjen plassen når teksten pagineres på nytt.
    var anchor by remember { mutableStateOf<Pair<Long, ReadingFlow.Anchor>?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(theme.background).windowInsetsPadding(WindowInsets.displayCutout)) {
        val pages = pagesOrNull
        val b = book
        if (pages == null || b == null) return@BoxWithConstraints
        if (pages.isEmpty()) {
            Text("Ingen sider er ferdig behandlet ennå.", color = theme.text, modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val hPad = 24.dp
        val topPad = 44.dp
        val bottomPad = 36.dp
        val widthPx = with(density) { (maxWidth - hPad * 2).toPx() }.toInt()
        val heightPx = with(density) { (maxHeight - topPad - bottomPad).toPx() }.toInt()
        val styles = remember(settings) { ReaderStyles(settings) }
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val pagerState = rememberPagerState { screens?.size ?: 0 }

        // Første gang: åpnet på en bestemt side, ellers der boka sist ble lest (med plassen på siden).
        if (anchor == null) {
            anchor = when {
                startPageId != null -> startPageId to ReadingFlow.Anchor(0, -1, 0)
                b.sistLestSide != null -> b.sistLestSide to ReadingFlow.Anchor(0, b.sistLestBlokk, b.sistLestTegn)
                else -> pages.first().id to ReadingFlow.Anchor(0, -1, 0)
            }
        }

        /** Skjermsiden for et ankre (side-id + plass); blokk -1 = begynnelsen av siden. */
        fun screenFor(target: Pair<Long, ReadingFlow.Anchor>, list: List<Screen>, f: List<ReadingFlow.Block>): Int {
            val pi = pages.indexOfFirst { it.id == target.first }
            if (pi < 0) return 0
            if (target.second.block >= 0) {
                val a = target.second.copy(page = pi)
                for ((bi, blk) in f.withIndex()) {
                    val pos = blk.positionOf(a) ?: continue
                    screenOf(list, bi, pos).takeIf { it >= 0 }?.let { return it }
                }
            }
            return list.indexOfFirst { pi in it.pages }.coerceAtLeast(0)
        }

        LaunchedEffect(pages, settings, widthPx, heightPx, missing) {
            val paginator = Paginator(
                measurer, density, styles, widthPx, heightPx,
                onFootnote = { pageIndex, number -> footnote = pageIndex to number },
                onPageLink = { number -> tocTarget = number },
            )
            val (newFlow, result) = withContext(Dispatchers.Default) {
                val f = vm.flowOf(pages, missing)
                // Ny innstilling mens vi holder på: stopp, den nye pagineringen tar over.
                f to paginator.paginate(f) { ensureActive() }
            }
            val target = anchor
            flow = newFlow
            screens = result
            val index = target?.let { screenFor(it, result, newFlow) } ?: 0
            pagerState.scrollToPage(index.coerceIn(0, (result.size - 1).coerceAtLeast(0)))
        }

        val list = screens
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        // Lesepunktet: begynnelsen av skjermsiden vi står på.
        LaunchedEffect(pagerState, list) {
            snapshotFlow { pagerState.currentPage }.collect { i ->
                val s = list.getOrNull(i) ?: return@collect
                val first = s.items.firstOrNull()
                val a = when (first) {
                    is ScreenItem.Text -> flow.getOrNull(first.block)?.anchorAt(first.start)
                    is ScreenItem.Box -> flow.getOrNull(first.block)?.anchorAt(first.start)
                    else -> null
                } ?: ReadingFlow.Anchor(s.pageIndex, -1, 0)
                val id = pages.getOrNull(a.page)?.id ?: return@collect
                anchor = id to a
                vm.saveLastRead(id, a.block.coerceAtLeast(0), a.offset)
            }
        }

        // Markeringer og bokmerke funnet igjen i lesestrømmen.
        val highlights = remember(flow, markings, pages) {
            markings.filter { it.type == Marking.MARKERING }.mapNotNull { m -> locate(m, flow, pages)?.let { m to it } }
        }
        val bookmark = remember(flow, markings, pages) {
            markings.firstOrNull { it.type == Marking.BOKMERKE }?.let { m -> locate(m, flow, pages)?.let { m to it } }
        }
        val bookmarkScreen = bookmark?.let { screenOf(list, it.second.block, it.second.start) } ?: -1

        val current = list.getOrNull(pagerState.currentPage)
        val currentPage = current?.let { pages.getOrNull(mainPage(it, flow)) }

        fun goToScreen(i: Int) { if (i in list.indices) scope.launch { pagerState.scrollToPage(i) } }

        // Antall skjermsider leses fra tilstanden og kan være oppdatert før denne listen (f.eks. når ny skrifttype
        // gir flere skjermsider): sidebladeren kan da spørre etter en side utenfor listen. Da tegnes ingenting.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { i -> list.getOrNull(i)?.let { s -> "$i-${s.pageIndex}" } ?: "utenfor-$i" },
        ) { i ->
            val screen = list.getOrNull(i) ?: return@HorizontalPager
            // Tekstbitene på skjermsiden med plassering, for å finne hvor et langt trykk traff.
            val hits = remember(screen) { mutableMapOf<Int, Pair<LayoutCoordinates, TextLayoutResult>>() }
            var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { root = it }.pointerInput(menu, screen, flow) {
                    detectTapGestures(
                        onTap = { o ->
                            val w = size.width
                            when {
                                menu -> menu = false
                                o.x < w / 3f -> scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                o.x > w * 2f / 3f -> scope.launch { if (pagerState.currentPage < list.size - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                else -> menu = true
                            }
                        },
                        onLongPress = { o ->
                            val r = root ?: return@detectTapGestures
                            for ((idx, hit) in hits) {
                                val (coords, layout) = hit
                                if (!coords.isAttached) continue
                                val local = coords.localPositionOf(r, o)
                                if (local.x < 0 || local.y < 0 || local.x > coords.size.width || local.y > coords.size.height) continue
                                val item = screen.items.getOrNull(idx) ?: continue
                                val (block, start) = when (item) {
                                    is ScreenItem.Text -> item.block to item.start
                                    is ScreenItem.Box -> item.block to item.start
                                    else -> continue
                                }
                                val text = flow.getOrNull(block)?.text ?: continue
                                val pos = (start + layout.getOffsetForPosition(local)).coerceIn(0, (text.length - 1).coerceAtLeast(0))
                                val sentence = ReadingFlow.sentenceAt(text, pos)
                                if (!sentence.isEmpty()) selection = Selection(block, sentence.first, sentence.last + 1, pos)
                                break
                            }
                        },
                    )
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = hPad, end = hPad, top = topPad).height(with(density) { heightPx.toDp() }),
                    verticalArrangement = Arrangement.spacedBy(with(density) { styles.spacing(density).toDp() }),
                ) {
                    ScreenContent(
                        screen, styles, theme,
                        highlights = highlights.map { it.second }, selection = selection,
                        bookmark = bookmark?.second,
                        onLayout = { idx, coords, layout -> hits[idx] = coords to layout },
                        onFigure = { path, d, page -> figure = Triple(path, d, pages.getOrNull(page)?.id) },
                        onPrev = { scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        onNext = { scope.launch { if (pagerState.currentPage < list.size - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    )
                }
                // Bokens sidetall (flere når skjermsiden går over et sideskift) og appens eget skjermsidenummer.
                val numbers = screen.pages.mapNotNull { pages.getOrNull(it)?.sidetallVisning }.distinct()
                Text(
                    (if (numbers.isEmpty()) "" else "s. ${numbers.joinToString("/")}   ") + "${i + 1} / ${list.size}",
                    color = theme.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
                // Bokmerket er et annet sted: liten knapp oppe til høyre for å gå dit.
                if (bookmarkScreen >= 0 && bookmarkScreen != i) {
                    IconButton(
                        onClick = { goToScreen(bookmarkScreen) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                            .semantics { contentDescription = "Gå til bokmerke" },
                    ) { Icon(Icons.Outlined.Bookmark, null, tint = RIBBON.copy(alpha = 0.6f)) }
                }
            }
        }

        // Menyen
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") }
                    Text(b.tittel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (bookmarkScreen >= 0) {
                        IconButton(onClick = { goToScreen(bookmarkScreen); menu = false }) { Icon(Icons.Outlined.Bookmark, "Gå til bokmerke", tint = RIBBON) }
                    }
                    IconButton(onClick = { showMarkings = true; menu = false }) { Icon(Icons.Outlined.FormatListBulleted, "Markeringer") }
                    IconButton(onClick = { showOriginal = true; menu = false }) { Icon(Icons.Outlined.Image, "Vis original") }
                    IconButton(onClick = { editing = true; menu = false }) { Icon(Icons.Outlined.Edit, "Rediger tekst") }
                    IconButton(onClick = { goTo = true }) { Icon(Icons.Outlined.Numbers, "Gå til side") }
                    if (vm.hasClaude && currentPage != null) {
                        IconButton(onClick = { vm.runClaude(currentPage); menu = false }) { Icon(Icons.Outlined.AutoAwesome, "Analyser siden med Claude") }
                    }
                }
            }
        }
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val screenIndex = pagerState.currentPage
                    var slider by remember(screenIndex) { mutableStateOf(screenIndex.toFloat()) }
                    var dragging by remember { mutableStateOf(false) }
                    // Mens sidebladeren dras: sidetall, kapittel og et lite utsnitt av skjermsiden den står på.
                    val shownIndex = if (dragging) slider.roundToInt().coerceIn(0, (list.size - 1).coerceAtLeast(0)) else screenIndex
                    val shown = list.getOrNull(shownIndex)
                    val shownPage = shown?.let { pages.getOrNull(mainPage(it, flow)) }
                    val numbers = shown?.pages?.mapNotNull { pages.getOrNull(it)?.sidetallVisning }?.distinct().orEmpty()
                    if (dragging && shown != null) {
                        chapterOf(shown, flow)?.let { title ->
                            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ScreenPreview(shown, styles, theme, widthPx, heightPx, hPad)
                    }
                    Text(
                        "Side ${numbers.joinToString("/").ifEmpty { "–" }} · skjermside ${shownIndex + 1} av ${list.size}" +
                            if (dragging) "" else if (shownPage?.ocrKilde == Page.KILDE_CLAUDE) " · Claude" else " · tekst fra telefonen",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = slider,
                        onValueChange = { slider = it; dragging = true },
                        onValueChangeFinished = { dragging = false; goToScreen(slider.roundToInt()) },
                        valueRange = 0f..(list.size - 1).coerceAtLeast(1).toFloat(),
                    )
                    if (bookmarkScreen >= 0) {
                        OutlinedButton(onClick = { goToScreen(bookmarkScreen); menu = false }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Bookmark, null, tint = RIBBON)
                            Text("  Gå til bokmerke (skjermside ${bookmarkScreen + 1})")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Skrift", modifier = Modifier.width(72.dp))
                        OutlinedButton(onClick = { vm.prefs.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12f)) } }) { Text("A−") }
                        Text("${settings.fontSize.toInt()}")
                        OutlinedButton(onClick = { vm.prefs.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36f)) } }, modifier = Modifier.semantics { contentDescription = "Større skrift" }) { Text("A+") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Linjer", modifier = Modifier.width(72.dp))
                        listOf(1.3f to "Tett", 1.5f to "Normal", 1.8f to "Luftig").forEach { (v, label) ->
                            FilterChip(selected = settings.lineHeight == v, onClick = { vm.prefs.update { it.copy(lineHeight = v) } }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Skrifttype", modifier = Modifier.width(72.dp))
                        FilterChip(selected = settings.serif, onClick = { vm.prefs.update { it.copy(serif = true) } }, label = { Text("Serif") }, modifier = Modifier.semantics { contentDescription = "Serif" })
                        FilterChip(selected = !settings.serif, onClick = { vm.prefs.update { it.copy(serif = false) } }, label = { Text("Sans") }, modifier = Modifier.semantics { contentDescription = "Sans" })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tema", modifier = Modifier.width(72.dp))
                        ReaderTheme.entries.forEach { t ->
                            FilterChip(selected = theme == t, onClick = { vm.prefs.update { it.copy(theme = t) } }, label = { Text(t.label) })
                        }
                    }
                }
            }
        }

        // Langt trykk: marker setningen (kan utvides), sett bokmerke, eller fjern en markering.
        selection?.let { sel ->
            val block = flow.getOrNull(sel.block)
            if (block == null) { selection = null; return@let }
            val text = block.text.substring(sel.start, sel.end)
            val existing = highlights.firstOrNull { (_, r) -> r.block == sel.block && sel.press >= r.start && sel.press < r.end }
            AlertDialog(
                onDismissRequest = { selection = null },
                title = { Text(if (existing != null) "Markert tekst" else "Valgt tekst") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("«${existing?.first?.tekst ?: text}»", maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (existing != null) {
                            OutlinedButton(onClick = { vm.removeMarking(existing.first.id); selection = null }, modifier = Modifier.fillMaxWidth()) {
                                Text("Fjern markeringen")
                            }
                        } else {
                            Button(
                                onClick = {
                                    val start = block.anchorAt(sel.start)
                                    val end = block.anchorAt(sel.end)
                                    val startId = pages.getOrNull(start.page)?.id
                                    val endId = pages.getOrNull(end.page)?.id
                                    if (startId != null && endId != null) vm.addHighlight(startId to start, endId to end, text)
                                    selection = null
                                },
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Marker teksten" },
                            ) { Text("Marker teksten") }
                            OutlinedButton(
                                onClick = {
                                    val r = ReadingFlow.extendToNextSentence(block.text, sel.start until sel.end)
                                    selection = sel.copy(end = r.last + 1)
                                },
                                enabled = sel.end < block.text.trimEnd().length,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Ta med neste setning") }
                        }
                        OutlinedButton(
                            onClick = {
                                val a = block.anchorAt(sel.start)
                                pages.getOrNull(a.page)?.id?.let { id ->
                                    vm.setBookmark(id, a.block, a.offset, block.text.substring(sel.start, minOf(block.text.length, sel.start + 40)))
                                }
                                selection = null
                            },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sett bokmerke her" },
                        ) { Text(if (bookmark != null) "Flytt bokmerket hit" else "Sett bokmerke her") }
                    }
                },
                confirmButton = { TextButton(onClick = { selection = null }) { Text("Lukk") } },
            )
        }

        if (showMarkings) {
            val ordered = highlights.sortedWith(compareBy({ it.second.block }, { it.second.start }))
            AlertDialog(
                onDismissRequest = { showMarkings = false },
                title = { Text("Markeringer") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bookmark != null) {
                            OutlinedButton(onClick = { goToScreen(bookmarkScreen); showMarkings = false }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Bookmark, null, tint = RIBBON)
                                Text("  Bokmerket (skjermside ${bookmarkScreen + 1})")
                            }
                            TextButton(onClick = { vm.removeBookmark() }) { Text("Fjern bokmerket") }
                        }
                        if (ordered.isEmpty()) {
                            Text("Ingen markert tekst ennå. Hold fingeren på en setning for å markere den.", style = MaterialTheme.typography.bodyMedium)
                        }
                        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(ordered, key = { it.first.id }) { (m, r) ->
                                val screenIndex = screenOf(list, r.block, r.start)
                                val pageNo = pages.getOrNull(flow.getOrNull(r.block)?.anchorAt(r.start)?.page ?: -1)?.sidetallVisning
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        Modifier.weight(1f).clickable { goToScreen(screenIndex); showMarkings = false },
                                    ) {
                                        Text(m.tekst, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                        Text("s. ${pageNo ?: "?"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { vm.removeMarking(m.id) }) { Icon(Icons.Outlined.Delete, "Fjern markeringen") }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showMarkings = false }) { Text("Lukk") } },
            )
        }

        if (showOriginal && currentPage != null) {
            OriginalDialog(currentPage, onDismiss = { showOriginal = false }, onMark = { vm.addRegion(currentPage, it) }, onClear = { vm.clearRegions(currentPage) })
        }
        if (editing && currentPage != null) {
            EditDialog(
                initial = vm.editableText(currentPage),
                edited = currentPage.redigertTekst != null,
                onSave = { vm.saveEdit(currentPage, it); editing = false },
                onReset = { vm.saveEdit(currentPage, null); editing = false },
                onDismiss = { editing = false },
            )
        }
        tocTarget?.let { number ->
            LaunchedEffect(number) {
                tocTarget = null
                val pageIndex = TocLinks.targetIndex(number, pages.map { it.sidetallVisning })
                val idx = pageIndex?.let { pi -> list.indexOfFirst { pi in it.pages } } ?: -1
                if (idx >= 0) pagerState.scrollToPage(idx)
                else android.widget.Toast.makeText(context, "Fant ikke side $number i boka", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (goTo) {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { goTo = false },
                title = { Text("Gå til side") },
                text = {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Sidetall (f.eks. 47 eller xii)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = text.trim().lowercase()
                        val pageIndex = pages.indexOfFirst { it.sidetallVisning?.trim('(', ')')?.lowercase() == t }
                            .takeIf { it >= 0 } ?: t.toIntOrNull()?.minus(1)?.takeIf { it in pages.indices }
                        val idx = pageIndex?.let { pi -> list.indexOfFirst { pi in it.pages } } ?: -1
                        goToScreen(idx)
                        goTo = false
                        menu = false
                    }) { Text("Gå") }
                },
                dismissButton = { TextButton(onClick = { goTo = false }) { Text("Avbryt") } },
            )
        }
        footnote?.let { (pageIndex, number) ->
            val note = remember(pageIndex, number) { vm.findFootnote(pageIndex, number) }
            AlertDialog(
                onDismissRequest = { footnote = null },
                title = { Text("Fotnote $number") },
                text = { Text(note ?: "Fant ikke teksten til fotnote $number i boka.") },
                confirmButton = { TextButton(onClick = { footnote = null }) { Text("Lukk") } },
            )
        }
        figure?.let { (path, description, pageId) -> FigureDialog(path, description, pageId, b.claudeModell) { figure = null } }
    }
}

/** Markeringer, utvalget og bokmerket lagt på et utsnitt av en blokk ([start] = utsnittets plass i blokken). */
private fun decorate(text: AnnotatedString, block: Int, start: Int, highlights: List<FlowRange>, selection: Selection?): AnnotatedString {
    val end = start + text.length
    val ranges = highlights.filter { it.block == block }.map { it.start to it.end to HIGHLIGHT } +
        listOfNotNull(selection?.takeIf { it.block == block }?.let { it.start to it.end to SELECTION })
    val visible = ranges.filter { (r, _) -> r.first < end && r.second > start }
    if (visible.isEmpty()) return text
    return AnnotatedString.Builder(text).apply {
        for ((r, color) in visible) {
            addStyle(SpanStyle(background = color), (r.first - start).coerceAtLeast(0), (r.second - start).coerceAtMost(text.length))
        }
    }.toAnnotatedString()
}

/** Kapitlet en skjermside hører til: nærmeste hovedkapittel (ellers overskrift) før eller øverst på skjermsiden. */
private fun chapterOf(screen: Screen, flow: List<ReadingFlow.Block>): String? {
    val first = screen.items.firstNotNullOfOrNull {
        when (it) {
            is ScreenItem.Text -> it.block
            is ScreenItem.Box -> it.block
            else -> null
        }
    } ?: return null
    val before = flow.take(first + 1).filter { it.type == BlockType.HEADING && !it.toc }
    return (before.lastOrNull { it.level == 1 } ?: before.lastOrNull())?.text?.replace('\n', ' ')
}

/** Et lite utsnitt av en skjermside, tegnet i leserens stil og skalert ned (vises mens sidebladeren dras). */
@Composable
private fun ScreenPreview(screen: Screen, styles: ReaderStyles, theme: ReaderTheme, widthPx: Int, heightPx: Int, hPad: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val scale = 0.4f
    val fullW = with(density) { widthPx.toDp() + hPad * 2 }
    val fullH = with(density) { heightPx.toDp() + hPad }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(fullW * scale, fullH * scale).clip(RoundedCornerShape(6.dp)).background(theme.background).clipToBounds()
                .semantics { contentDescription = "Utsnitt av siden" },
        ) {
            Column(
                Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(fullW, fullH)
                    .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
                    .padding(start = hPad, end = hPad, top = hPad / 2),
                verticalArrangement = Arrangement.spacedBy(with(density) { styles.spacing(density).toDp() }),
            ) {
                ScreenContent(
                    screen, styles, theme, highlights = emptyList(), selection = null, bookmark = null,
                    onLayout = { _, _, _ -> }, onFigure = { _, _, _ -> }, onPrev = {}, onNext = {},
                )
            }
        }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    styles: ReaderStyles,
    theme: ReaderTheme,
    highlights: List<FlowRange>,
    selection: Selection?,
    bookmark: FlowRange?,
    onLayout: (Int, LayoutCoordinates, TextLayoutResult) -> Unit,
    onFigure: (String, String?, Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val density = LocalDensity.current

    /** Teksten med markeringer, og et lite bokmerkebånd i margen ved linjen der bokmerket står. */
    @Composable
    fun MarkedText(index: Int, text: AnnotatedString, block: Int, start: Int, style: androidx.compose.ui.text.TextStyle) {
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val mark = bookmark?.takeIf { it.block == block && it.start >= start && it.start < start + text.length }?.let { it.start - start }
        Text(
            decorate(text, block, start, highlights, selection),
            style = style, color = theme.text,
            onTextLayout = { l -> layout = l; coords?.let { onLayout(index, it, l) } },
            modifier = Modifier.fillMaxWidth()
                .onGloballyPositioned { c -> coords = c; layout?.let { onLayout(index, c, it) } }
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    if (mark == null) return@drawBehind
                    val line = l.getLineForOffset(mark)
                    val top = l.getLineTop(line)
                    val bottom = l.getLineBottom(line)
                    val w = 6.dp.toPx()
                    drawRect(RIBBON, topLeft = Offset(-w - 6.dp.toPx(), top), size = Size(w, bottom - top))
                },
        )
    }

    for ((index, item) in screen.items.withIndex()) {
        when (item) {
            is ScreenItem.Text -> MarkedText(index, item.text, item.block, item.start, styles.of(item.kind))
            is ScreenItem.Box -> {
                val pad = with(density) { styles.boxPadding(density).toDp() }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(theme.box).padding(pad),
                ) {
                    item.title?.let {
                        Text(it, style = styles.boxTitle, color = theme.text)
                        Spacer(Modifier.height(with(density) { (styles.spacing(density) / 2).toDp() }))
                    }
                    MarkedText(index, item.text, item.block, item.start, styles.boxText)
                }
            }
            is ScreenItem.Figure -> {
                val h = with(density) { item.height.toDp() }
                Box(
                    Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp))
                        .then(
                            if (item.image != null) {
                                // Samme tappsoner som resten av siden: bare midten åpner figuren i fullskjerm.
                                Modifier.pointerInput(item.image) {
                                    detectTapGestures { o ->
                                        val third = size.width / 3f
                                        when {
                                            o.x < third -> onPrev()
                                            o.x > third * 2 -> onNext()
                                            else -> onFigure(item.image, item.description, item.page)
                                        }
                                    }
                                }
                            } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.image != null) {
                        AsyncImage(model = File(item.image), contentDescription = item.description ?: "Figur", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("[Figur] ${item.description ?: ""}", style = styles.caption, color = theme.muted, textAlign = TextAlign.Center)
                    }
                }
            }
            ScreenItem.Empty -> Text("(Tom side)", style = styles.caption, color = theme.muted, fontWeight = FontWeight.Normal)
            is ScreenItem.Missing -> Column(
                Modifier.fillMaxWidth().padding(top = 80.dp).clip(RoundedCornerShape(8.dp)).background(theme.box).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Side ${item.label} er ikke lagt inn ennå",
                    style = styles.heading, color = theme.text, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ta bilder av ${if (item.count == 1) "siden" else "sidene"} og legg dem inn fra bokskjermen. De sorteres inn på riktig plass.",
                    style = styles.caption, color = theme.muted, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
