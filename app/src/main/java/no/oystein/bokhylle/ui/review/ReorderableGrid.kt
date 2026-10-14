package no.oystein.bokhylle.ui.review

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Rutenett der elementene kan flyttes: hold inne og dra. [onMove] får den nye rekkefølgen og hvilket element som ble flyttet.
 * Rutenettet ruller av seg selv når elementet dras mot toppen eller bunnen.
 */
@Composable
fun <T> ReorderableGrid(
    items: List<T>,
    key: (T) -> Long,
    onMove: (newOrder: List<Long>, movedKey: Long) -> Unit,
    modifier: Modifier = Modifier,
    minCellSize: Dp = 110.dp,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    header: (@Composable () -> Unit)? = null,
    dragEnabled: Boolean = true,
    content: @Composable (item: T, dragging: Boolean) -> Unit,
) {
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val local = remember { mutableStateListOf<T>() }
    var draggingKey by remember { mutableStateOf<Long?>(null) }
    // Ny rekkefølge fra databasen når ingen drar.
    LaunchedEffect(items, draggingKey) { if (draggingKey == null) { local.clear(); local.addAll(items) } }

    var startItemOffset by remember { mutableStateOf(Offset.Zero) }
    var startTouch by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val onMoveState by rememberUpdatedState(onMove)

    fun itemAt(pos: Offset): LazyGridItemInfo? = state.layoutInfo.visibleItemsInfo.firstOrNull {
        it.key is Long && pos.x >= it.offset.x && pos.x <= it.offset.x + it.size.width &&
            pos.y >= it.offset.y && pos.y <= it.offset.y + it.size.height
    }

    // Autorulling mens man drar nær kanten.
    LaunchedEffect(draggingKey) {
        while (draggingKey != null && isActive) {
            val y = startTouch.y + dragDelta.y
            val height = state.layoutInfo.viewportSize.height
            val edge = 120f
            val speed = when {
                y < edge -> -(edge - y) / 4f
                y > height - edge -> (y - (height - edge)) / 4f
                else -> 0f
            }
            if (speed != 0f) state.scrollBy(speed)
            delay(16)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCellSize),
        state = state,
        modifier = modifier.pointerInput(dragEnabled) {
            if (!dragEnabled) return@pointerInput
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    val info = itemAt(offset) ?: return@detectDragGesturesAfterLongPress
                    draggingKey = info.key as Long
                    startItemOffset = Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
                    startTouch = offset
                    dragDelta = Offset.Zero
                },
                onDrag = { change, amount ->
                    val dragged = draggingKey ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragDelta += amount
                    val target = itemAt(startTouch + dragDelta) ?: return@detectDragGesturesAfterLongPress
                    val targetKey = target.key as Long
                    if (targetKey != dragged) {
                        val from = local.indexOfFirst { key(it) == dragged }
                        val to = local.indexOfFirst { key(it) == targetKey }
                        if (from >= 0 && to >= 0) local.add(to, local.removeAt(from))
                    }
                },
                onDragEnd = {
                    val dragged = draggingKey
                    if (dragged != null) {
                        val order = local.map(key)
                        if (order != items.map(key)) onMoveState(order, dragged)
                    }
                    draggingKey = null
                },
                onDragCancel = { draggingKey = null },
            )
        },
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "topp") { header() }
        itemsIndexed(local, key = { _, it -> key(it) }) { _, item ->
            val k = key(item)
            val dragging = k == draggingKey
            val mod = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer {
                    // Hold elementet under fingeren selv om det har byttet plass i rutenettet.
                    val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k }
                    val now = info?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) } ?: startItemOffset
                    val t = startItemOffset + dragDelta - now
                    translationX = t.x
                    translationY = t.y
                    scaleX = 1.06f
                    scaleY = 1.06f
                    shadowElevation = 12f
                }
            } else {
                Modifier.animateItem()
            }
            androidx.compose.foundation.layout.Box(mod) { content(item, dragging) }
        }
    }
}
