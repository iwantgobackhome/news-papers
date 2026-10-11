package app.fractal.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.*
import app.fractal.design.LocalFractalColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

internal fun pageJumpIndex(input: String, total: Int): Int? =
    if (total <= 0) null else input.trim().toLongOrNull()?.coerceIn(1L, total.toLong())?.toInt()?.minus(1)

internal fun pageScrollPosition(index: Int, fraction: Double, total: Int): Float =
    if (total <= 1) 0f else ((index + fraction.coerceIn(0.0, 1.0)) / (total - 1)).toFloat().coerceIn(0f, 1f)

internal fun pageAtScrollPosition(position: Float, total: Int): Int =
    if (total <= 1 || !position.isFinite()) 0 else (position.coerceIn(0f, 1f) * (total - 1)).roundToInt()

@Composable
internal fun ReaderPageScrollbar(state: LazyListState, total: Int, modifier: Modifier = Modifier,
    position: () -> Float, onPage: (Int) -> Unit) {
    if (total <= 0) return
    val colors = LocalFractalColors.current
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var draggedPage by remember { mutableIntStateOf(1) }
    val latestOnPage by rememberUpdatedState(onPage)
    LaunchedEffect(state, dragging) {
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress) }
            .collectLatest {
                visible = true
                if (!dragging && !it.third) { delay(1500); visible = false }
            }
    }
    val opacity by animateFloatAsState(if (visible || dragging) 1f else 0f, label = "page scrollbar")
    val thumbHeight = 40.dp
    val thumbPx = with(LocalDensity.current) { thumbHeight.toPx() }
    BoxWithConstraints(modifier.width(24.dp).fillMaxHeight().nativeInkBlocker()
        .pointerInput(total, thumbPx) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                dragging = true
                var requestedPage = -1
                fun move(y: Float) {
                    val page = pageAtScrollPosition((y - thumbPx / 2) / (size.height - thumbPx).coerceAtLeast(1f), total)
                    draggedPage = page + 1
                    if (page != requestedPage) { requestedPage = page; latestOnPage(page) }
                }
                try {
                    move(down.position.y)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (pointer.pressed) move(pointer.position.y)
                    } while (event.changes.any { it.id == down.id && it.pressed })
                } finally { dragging = false }
            }
        }) {
        val y = (maxHeight - thumbHeight).coerceAtLeast(0.dp) * position()
        Box(Modifier.offset(y = y).align(Alignment.TopCenter).width(6.dp).height(thumbHeight)
            .alpha(opacity).background(colors.inkSoft, RoundedCornerShape(3.dp)))
        if (dragging) Text(stringResource(R.string.reader_page_count, draggedPage, total),
            Modifier.align(Alignment.TopEnd).offset(x = (-28).dp, y = y).wrapContentSize(Alignment.CenterEnd, unbounded = true)
                .background(colors.paper, RoundedCornerShape(8.dp)).padding(8.dp),
            color = colors.ink, style = MaterialTheme.typography.labelLarge)
    }
}
