package com.comicmanager.ui.screens

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Position and size are in pixels in the unpadded grid's coordinate space. */
internal data class ShelfReorderDrag(
    val comicId: Long,
    val targetId: Long?,
    val topLeft: IntOffset,
    val size: IntSize
)

private data class ReorderHold(val comicId: Long, val grabOffset: Offset, val itemSize: IntSize)

@Composable
internal fun Modifier.shelfDragReorder(
    enabled: Boolean,
    orderedIds: List<Long>,
    gridState: LazyGridState,
    onDragChanged: (ShelfReorderDrag?) -> Unit,
    onMove: (Long, Long) -> Unit
): Modifier {
    val updateDrag by rememberUpdatedState(onDragChanged)
    val commitMove by rememberUpdatedState(onMove)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scrollEdge = with(density) { 64.dp.toPx() }
    val scrollSpeed = with(density) { 850.dp.toPx() }
    var hold by remember { mutableStateOf<ReorderHold?>(null) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }

    fun targetAt(position: Offset, comicId: Long): Long? {
        val layout = gridState.layoutInfo
        val size = layout.viewportSize
        if (position.x < 0f || position.x >= size.width || position.y < 0f || position.y >= size.height) {
            return null
        }
        val slot = layout.visibleItemsInfo.minByOrNull { it.distanceSquaredTo(position) }?.index
        // The preview can move keys between cells. Map hit cells back to the stable base order.
        return slot?.let(orderedIds::getOrNull)?.takeUnless { it == comicId }
    }

    fun dispatchPreview(held: ReorderHold, position: Offset) {
        val topLeft = position - held.grabOffset
        updateDrag(
            ShelfReorderDrag(
                comicId = held.comicId,
                targetId = targetAt(position, held.comicId),
                topLeft = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                size = held.itemSize
            )
        )
    }

    LaunchedEffect(hold, gridState, orderedIds) {
        val held = hold ?: return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (hold === held) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = frame
            val height = gridState.layoutInfo.viewportSize.height.toFloat()
            if (height <= 0f) continue
            val edge = minOf(scrollEdge, height / 3f)
            val y = pointerPosition.y
            val distance = when {
                y < edge -> y - edge
                y > height - edge -> y - (height - edge)
                else -> 0f
            }
            val speed = (distance / edge).coerceIn(-1f, 1f) * scrollSpeed
            if (speed != 0f && gridState.scrollBy(speed * seconds) != 0f && hold === held) {
                dispatchPreview(held, pointerPosition)
            }
        }
    }

    if (!enabled || orderedIds.size < 2) return this
    return pointerInput(gridState, orderedIds) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val comicId = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(down.position) }
                ?.key as? Long ?: return@awaitEachGesture
            val longPress = awaitReorderHold(down) ?: return@awaitEachGesture
            val picked = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                it.key == comicId && it.contains(longPress.position)
            } ?: return@awaitEachGesture
            val origin = Offset(picked.offset.x.toFloat(), picked.offset.y.toFloat())
            val held = ReorderHold(comicId, longPress.position - origin, picked.size)
            longPress.consume()
            pointerPosition = longPress.position
            hold = held
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var targetId: Long? = null
            try {
                dispatchPreview(held, pointerPosition)
                while (true) {
                    // Take moves before the lazy grid or the horizontal pager can claim them.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    change.consume()
                    if (!change.pressed) {
                        targetId = targetAt(change.position, comicId)
                        break
                    }
                    pointerPosition = change.position
                    dispatchPreview(held, pointerPosition)
                }
            } finally {
                hold = null
                updateDrag(null)
            }
            targetId?.let { commitMove(comicId, it) }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitReorderHold(down: PointerInputChange): PointerInputChange? {
    var last = down
    val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed || change.isConsumed ||
                event.changes.any { it.id != down.id && it.pressed } ||
                (change.position - down.position).getDistance() >= viewConfiguration.touchSlop
            ) break
            last = change
        }
        true
    } == true
    return last.takeUnless { interrupted }
}

private fun LazyGridItemInfo.contains(position: Offset): Boolean =
    position.x >= offset.x && position.x < offset.x + size.width &&
        position.y >= offset.y && position.y < offset.y + size.height

private fun LazyGridItemInfo.distanceSquaredTo(position: Offset): Float {
    val dx = position.x - position.x.coerceIn(offset.x.toFloat(), (offset.x + size.width).toFloat())
    val dy = position.y - position.y.coerceIn(offset.y.toFloat(), (offset.y + size.height).toFloat())
    return dx * dx + dy * dy
}
