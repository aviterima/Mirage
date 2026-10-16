package com.mirage.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mirage.spike.engine.ItineraryStop
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Drag only the handle; ordinary swipes still scroll the stop list. Commit once on drop. */
@Composable
internal fun ReorderableStopList(
    stops: List<ItineraryStop>,
    onMove: (Int, Int) -> Unit,
    parentScroll: ScrollState? = null,
    parentViewport: Rect = Rect.Zero,
    row: @Composable (Int, ItineraryStop, Modifier) -> Unit,
) {
    val ownScroll = rememberScrollState()
    val scroll = parentScroll ?: ownScroll
    val latestParentViewport by rememberUpdatedState(parentViewport)
    var listTop by remember { mutableFloatStateOf(0f) }
    val handleCoordinates = remember(stops) { mutableMapOf<Int, LayoutCoordinates>() }
    var pointerRootY by remember(stops) { mutableFloatStateOf(Float.NaN) }
    val heights = remember(stops) { mutableStateMapOf<Int, Int>() }
    var dragging by remember(stops) { mutableIntStateOf(-1) }
    var distance by remember(stops) { mutableFloatStateOf(0f) }
    var initialScroll by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { 2.dp.toPx() }
    val edge = with(LocalDensity.current) { 40.dp.toPx() }
    val speed = with(LocalDensity.current) { 8.dp.toPx() }
    fun center(index: Int): Float = (0 until index).sumOf { heights[it] ?: 0 }.toFloat() +
        gap * index + (heights[index] ?: 0) / 2f
    val translation = distance + scroll.value - initialScroll
    val target = if (dragging < 0) -1 else stops.indices.minByOrNull {
        abs(center(it) - (center(dragging) + translation))
    } ?: dragging

    // Continue scrolling while the handle is held near either edge, including long lists.
    LaunchedEffect(dragging, scroll) {
        if (dragging < 0) return@LaunchedEffect
        while (true) {
            val visibleHeight = if (parentScroll == null) viewport.toFloat() else latestParentViewport.height
            val viewportTop = if (parentScroll == null) listTop else latestParentViewport.top
            // Use the finger, not the row center: handles sit near the top of tall
            // rows. Root coordinates stay fixed while the content scrolls beneath it.
            val y = pointerRootY - viewportTop
            if (y.isFinite() && visibleHeight > 0f) when {
                y < edge -> scroll.scrollBy(-speed)
                y > visibleHeight - edge -> scroll.scrollBy(speed)
            }
            delay(16)
        }
    }
    // The planner already scrolls. Sharing that viewport avoids a second, partly
    // clipped scroll region; the live itinerary sheet keeps its bounded list.
    val container = if (parentScroll == null) Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.30f).dp)
        else Modifier
    Box(container.onSizeChanged { viewport = it.height }
        .onGloballyPositioned { listTop = it.positionInRoot().y }) {
        Column((if (parentScroll == null) Modifier.verticalScroll(scroll) else Modifier).padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            stops.forEachIndexed { index, stop ->
                val active = index == dragging
                Box(Modifier.fillMaxWidth().onSizeChanged { heights[index] = it.height }
                    .zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) translation else 0f; shadowElevation = if (active) 8f else 0f }
                    .background(if (active || index == target) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface)) {
                    val handle = Modifier.semantics {
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(index, index - 1); true })
                            if (index < stops.lastIndex) add(CustomAccessibilityAction("Move down") { onMove(index, index + 1); true })
                        }
                    }.onGloballyPositioned { handleCoordinates[index] = it }.pointerInput(stops, index) {
                        detectDragGestures(
                            onDragStart = { position ->
                                pointerRootY = handleCoordinates[index]?.takeIf { it.isAttached }?.localToRoot(position)?.y ?: Float.NaN
                                dragging = index; distance = 0f; initialScroll = scroll.value
                            },
                            onDragCancel = { dragging = -1; distance = 0f; pointerRootY = Float.NaN },
                            onDragEnd = {
                                val destination = stops.indices.minByOrNull {
                                    abs(center(it) - (center(index) + distance + scroll.value - initialScroll))
                                } ?: index
                                dragging = -1; distance = 0f; pointerRootY = Float.NaN
                                onMove(index, destination)
                            },
                            onDrag = { change, amount ->
                                // Read before updating the translated row. The first event
                                // already includes touch slop in change.position.
                                pointerRootY = handleCoordinates[index]?.takeIf { it.isAttached }?.localToRoot(change.position)?.y ?: Float.NaN
                                change.consume(); distance += amount.y
                            },
                        )
                    }
                    row(index, stop, handle)
                }
            }
        }
    }
}
