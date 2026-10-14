package no.oystein.bokhylle.ui.reader

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.oystein.bokhylle.app
import no.oystein.bokhylle.data.Page
import no.oystein.bokhylle.data.sidetallVisning
import java.io.File

/** Skjermside-nummer for markeringen av manglende sider (skiller den fra boksidens egne skjermsider). */
private const val MISSING_PART = 10_000

/**
 * Leseren: én bokside per skjerm (delt over flere skjermsider om nødvendig), bla med trykk eller sveip.
 * Høyre tredjedel = neste, venstre = forrige, midten = meny.
 */
@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit, startPageId: Long? = null) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(key = "les-$bookId") { ReaderViewModel(bookId, context.app) }
    val book by vm.book.collectAsState()
    val pagesOrNull by vm.pages.collectAsState()
    val settings by vm.prefs.settings.collectAsState()
    val missing by vm.missing.collectAsState()
    val theme = settings.theme
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var footnote by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var figure by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var showOriginal by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var screens by remember { mutableStateOf<List<Screen>?>(null) }
    // Boksiden vi står på; brukes til å finne igjen plassen når teksten pagineres på nytt.
    var anchorPage by remember { mutableStateOf(startPageId) }

    BoxWithConstraints(Modifier.fillMaxSize().background(theme.background)) {
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

        LaunchedEffect(pages, settings, widthPx, heightPx, missing) {
            val paginator = Paginator(measurer, density, styles, widthPx, heightPx) { pageIndex, number -> footnote = pageIndex to number }
            val result = withContext(Dispatchers.Default) {
                pages.flatMapIndexed { i, p ->
                    val own = paginator.paginate(i, vm.layoutOf(p))
                    // Mangler det sider etter denne, vises det tydelig i stedet for å hoppe over dem i stillhet.
                    val gaps = missing.filter { it.afterPageId == p.id }
                        .map { Screen(i, MISSING_PART, listOf(ScreenItem.Missing(it.label, it.count))) }
                    own + gaps
                }
            }
            val target = anchorPage ?: b.sistLestSide
            val oldScreen = screens?.getOrNull(pagerState.currentPage)
            screens = result
            val index = result.indexOfFirst { pages[it.pageIndex].id == target }.coerceAtLeast(0)
            // Samme skjermside innenfor boksiden hvis den fortsatt finnes.
            val part = if (oldScreen != null && pages.getOrNull(oldScreen.pageIndex)?.id == target) oldScreen.part else 0
            val exact = result.indexOfFirst { pages[it.pageIndex].id == target && it.part == part }
            pagerState.scrollToPage((if (exact >= 0) exact else index).coerceIn(0, (result.size - 1).coerceAtLeast(0)))
        }
        LaunchedEffect(pagerState, screens) {
            snapshotFlow { pagerState.currentPage }.collect { i ->
                val s = screens?.getOrNull(i) ?: return@collect
                val id = pages.getOrNull(s.pageIndex)?.id ?: return@collect
                anchorPage = id
                vm.saveLastRead(id)
            }
        }

        val list = screens
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }
        val current = list.getOrNull(pagerState.currentPage)
        val currentPage = current?.let { pages.getOrNull(it.pageIndex) }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), key = { "${list[it].pageIndex}-${list[it].part}" }) { i ->
            val screen = list[i]
            val page = pages[screen.pageIndex]
            Box(
                Modifier.fillMaxSize().pointerInput(menu) {
                    detectTapGestures { o ->
                        val w = size.width
                        when {
                            menu -> menu = false
                            o.x < w / 3f -> scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            o.x > w * 2f / 3f -> scope.launch { if (pagerState.currentPage < list.size - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            else -> menu = true
                        }
                    }
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = hPad, end = hPad, top = topPad).height(with(density) { heightPx.toDp() }).clipToBounds(),
                    verticalArrangement = Arrangement.spacedBy(with(density) { styles.spacing(density).toDp() }),
                ) {
                    ScreenContent(
                        screen, styles, theme,
                        onFigure = { path, d -> figure = path to d },
                        onPrev = { scope.launch { if (pagerState.currentPage > 0) pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        onNext = { scope.launch { if (pagerState.currentPage < list.size - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    )
                }
                Text(
                    "${page.sidetallVisning ?: ""}   ${screen.pageIndex + 1} / ${pages.size}",
                    color = theme.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        }

        // Menyen
        AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") }
                    Text(b.tittel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
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
                    val pageIndex = current?.pageIndex ?: 0
                    Text(
                        "Side ${currentPage?.sidetallVisning ?: "–"} · ${pageIndex + 1} av ${pages.size}" +
                            if (currentPage?.ocrKilde == Page.KILDE_CLAUDE) " · Claude" else " · tekst fra telefonen",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    var slider by remember(pageIndex) { mutableStateOf(pageIndex.toFloat()) }
                    Slider(
                        value = slider,
                        onValueChange = { slider = it },
                        onValueChangeFinished = {
                            val target = slider.toInt()
                            val idx = list.indexOfFirst { it.pageIndex == target }
                            if (idx >= 0) scope.launch { pagerState.scrollToPage(idx) }
                        },
                        valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Skrift", modifier = Modifier.width(72.dp))
                        OutlinedButton(onClick = { vm.prefs.update { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(12f)) } }) { Text("A−") }
                        Text("${settings.fontSize.toInt()}")
                        OutlinedButton(onClick = { vm.prefs.update { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(36f)) } }) { Text("A+") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Linjer", modifier = Modifier.width(72.dp))
                        listOf(1.3f to "Tett", 1.5f to "Normal", 1.8f to "Luftig").forEach { (v, label) ->
                            FilterChip(selected = settings.lineHeight == v, onClick = { vm.prefs.update { it.copy(lineHeight = v) } }, label = { Text(label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Skrifttype", modifier = Modifier.width(72.dp))
                        FilterChip(selected = settings.serif, onClick = { vm.prefs.update { it.copy(serif = true) } }, label = { Text("Serif") })
                        FilterChip(selected = !settings.serif, onClick = { vm.prefs.update { it.copy(serif = false) } }, label = { Text("Sans") })
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
                        val idx = pageIndex?.let { pi -> list.indexOfFirst { it.pageIndex == pi } } ?: -1
                        if (idx >= 0) scope.launch { pagerState.scrollToPage(idx) }
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
        figure?.let { (path, description) -> FigureDialog(path, description) { figure = null } }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    styles: ReaderStyles,
    theme: ReaderTheme,
    onFigure: (String, String?) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val density = LocalDensity.current
    for (item in screen.items) {
        when (item) {
            is ScreenItem.Text -> Text(item.text, style = styles.of(item.kind), color = theme.text, modifier = Modifier.fillMaxWidth())
            is ScreenItem.Box -> {
                val pad = with(density) { styles.boxPadding(density).toDp() }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(theme.box).padding(pad),
                ) {
                    item.title?.let {
                        Text(it, style = styles.boxTitle, color = theme.text)
                        Spacer(Modifier.height(with(density) { (styles.spacing(density) / 2).toDp() }))
                    }
                    Text(item.text, style = styles.boxText, color = theme.text)
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
                                            else -> onFigure(item.image, item.description)
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
