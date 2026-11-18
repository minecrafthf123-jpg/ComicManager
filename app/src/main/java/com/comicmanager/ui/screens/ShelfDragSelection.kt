package com.comicmanager.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.comicmanager.util.DragSelectionSession
import kotlin.math.abs

private enum class SelectionGestureStart { DRAG, CANCEL }

/** Horizontal swipes select; a vertical swipe still scrolls until a selection drag starts. */
@Composable
internal fun Modifier.shelfDragSelection(
    enabled: Boolean,
    orderedIds: List<Long>,
    selectedIds: Set<Long>,
    gridState: LazyGridState,
    onSelectionChange: (Set<Long>) -> Unit
): Modifier {
    val latestSelected by rememberUpdatedState(selectedIds)
    val latestOnSelectionChange by rememberUpdatedState(onSelectionChange)
    var dragSession by remember { mutableStateOf<DragSelectionSession?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var lastSelectionIndex by remember { mutableIntStateOf(-1) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val maximumSpeed = with(LocalDensity.current) { 900.dp.toPx() }

    fun indexAt(position: Offset, exact: Boolean): Int? {
        val visible = gridState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return null
        val hit = visible.firstOrNull { item ->
            position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
                position.y >= item.offset.y && position.y < item.offset.y + item.size.height
        }
        if (hit != null || exact) return hit?.index
        // Keep the gesture continuous across the spaces between cards and at viewport edges.
        return visible.minByOrNull { item ->
            val dx = position.x - position.x.coerceIn(
                item.offset.x.toFloat(), (item.offset.x + item.size.width).toFloat()
            )
            val dy = position.y - position.y.coerceIn(
                item.offset.y.toFloat(), (item.offset.y + item.size.height).toFloat()
            )
            dx * dx + dy * dy
        }?.index
    }

    fun updateSelection(position: Offset) {
        val session = dragSession ?: return
        val index = indexAt(position, exact = false) ?: return
        if (index == lastSelectionIndex) return
        lastSelectionIndex = index
        latestOnSelectionChange(session.selectionAt(index))
    }

    LaunchedEffect(dragSession) {
        if (dragSession == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (true) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = frame
            val position = dragPosition ?: break
            val height = viewportSize.height.toFloat()
            if (height <= 0f) continue
            val edge = minOf(edgeSize, height / 3f)
            val speed = when {
                position.y < edge -> -maximumSpeed * ((edge - position.y) / edge).coerceIn(0f, 1f)
                position.y > height - edge -> maximumSpeed * ((position.y - height + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f && gridState.scrollBy(speed * seconds) != 0f) {
                updateSelection(position)
            }
        }
    }

    return this.onSizeChanged { viewportSize = it }.then(
        if (!enabled) Modifier else Modifier.pointerInput(orderedIds, gridState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val anchorIndex = indexAt(down.position, exact = true) ?: return@awaitEachGesture
                var latestChange = down
                val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var outcome: SelectionGestureStart? = null
                    while (outcome == null) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed ||
                            event.changes.count { it.pressed } > 1
                        ) {
                            outcome = SelectionGestureStart.CANCEL
                        } else {
                            latestChange = change
                            val distance = change.position - down.position
                            if (distance.getDistance() >= viewConfiguration.touchSlop) {
                                outcome = if (abs(distance.x) > abs(distance.y)) {
                                    SelectionGestureStart.DRAG
                                } else {
                                    SelectionGestureStart.CANCEL
                                }
                            }
                        }
                    }
                    outcome
                }
                // A timeout means a stationary long press: allow dragging in any direction.
                if (start == SelectionGestureStart.CANCEL) return@awaitEachGesture
                latestChange.consume()
                val selectionBeforeDrag = latestSelected
                lastSelectionIndex = -1
                dragSession = DragSelectionSession(orderedIds, selectionBeforeDrag, anchorIndex)
                dragPosition = latestChange.position
                updateSelection(latestChange.position)
                var committed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                        change.consume()
                        if (!change.pressed) {
                            committed = true
                            break
                        }
                        dragPosition = change.position
                        updateSelection(change.position)
                    }
                } finally {
                    dragSession = null
                    dragPosition = null
                    lastSelectionIndex = -1
                    // Sorting, filtering, a second finger or a cancelled pointer cancels the
                    // range gesture. A normal release commits the last visible range.
                    if (!committed) latestOnSelectionChange(selectionBeforeDrag)
                }
            }
        }
    )
}
