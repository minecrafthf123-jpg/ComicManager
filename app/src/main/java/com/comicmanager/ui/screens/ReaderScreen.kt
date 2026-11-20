package com.comicmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.comicmanager.R
import com.comicmanager.ui.viewmodel.ReaderPage
import com.comicmanager.ui.viewmodel.ReaderUiState
import com.comicmanager.ui.viewmodel.ReaderViewModel
import com.comicmanager.util.ReaderZoomGeometry
import com.comicmanager.util.ReaderPlaybackSpeeds
import com.comicmanager.util.ReaderScrollAccumulator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

private val ReaderPauseIcon = ImageVector.Builder("ReaderPause", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 5f)
        verticalLineTo(19f)
        horizontalLineTo(10f)
        verticalLineTo(5f)
        close()
        moveTo(14f, 5f)
        verticalLineTo(19f)
        horizontalLineTo(18f)
        verticalLineTo(5f)
        close()
    }
}.build()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var showPlaybackSettings by remember { mutableStateOf(false) }
    val leaveReader = {
        viewModel.pauseAutoPlay()
        viewModel.saveProgress()
        onBack()
    }
    BackHandler(onBack = leaveReader)

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.pauseAutoPlay()
                viewModel.saveProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseAutoPlay()
            viewModel.saveProgress()
        }
    }
    DisposableEffect(view, state.isAutoPlaying) {
        val previous = view.keepScreenOn
        if (state.isAutoPlaying) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.comicName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = leaveReader) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.totalPages > 0) {
                        Text("${state.currentPage + 1} / ${state.totalPages}", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = viewModel::toggleMode) {
                        Icon(
                            if (state.isPagerMode) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            stringResource(if (state.isPagerMode) R.string.switch_scroll_mode else R.string.switch_page_mode)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            if (!state.isLoading && state.pages.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = viewModel::toggleAutoPlay) {
                            Icon(
                                if (state.isAutoPlaying) ReaderPauseIcon else Icons.Filled.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(if (state.isAutoPlaying) R.string.reader_v2_pause else R.string.reader_v2_play))
                        }
                        TextButton(onClick = {
                            viewModel.pauseAutoPlay()
                            showPlaybackSettings = true
                        }) {
                            Text(
                                if (state.isPagerMode) stringResource(R.string.reader_v2_page_interval, state.pageIntervalSeconds)
                                else scrollSpeedDescription(state.scrollSpeed)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.pages.isEmpty() -> Text(stringResource(R.string.no_images), Modifier.align(Alignment.Center))
                else -> key(state.isPagerMode) {
                    if (state.isPagerMode) {
                        PagerReader(
                            state = state,
                            onPageChanged = viewModel::updateCurrentPage,
                            onPause = viewModel::pauseAutoPlay
                        )
                    } else {
                        ScrollReader(
                            state = state,
                            onPageChanged = viewModel::updateCurrentPage,
                            onPause = viewModel::pauseAutoPlay
                        )
                    }
                }
            }
        }
    }
    if (showPlaybackSettings) {
        PlaybackSettingsDialog(state, viewModel, onDismiss = { showPlaybackSettings = false })
    }
}

@Composable
private fun scrollSpeedLabel(speed: Int): String = stringResource(
    when (speed) {
        1 -> R.string.reader_v2_speed_slowest
        2 -> R.string.reader_v2_speed_slow
        4 -> R.string.reader_v2_speed_fast
        5 -> R.string.reader_v2_speed_fastest
        else -> R.string.reader_v2_speed_normal
    }
)

@Composable
private fun scrollSpeedDescription(speed: Int): String = stringResource(
    R.string.reader_v2_scroll_speed,
    scrollSpeedLabel(speed),
    ReaderPlaybackSpeeds.screensPerSecond(speed).toString()
)

@Composable
private fun PlaybackSettingsDialog(state: ReaderUiState, viewModel: ReaderViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_v2_settings)) },
        text = {
            Column {
                if (state.isPagerMode) {
                    Text(stringResource(R.string.reader_v2_page_interval, state.pageIntervalSeconds))
                    Slider(
                        value = state.pageIntervalSeconds.toFloat(),
                        onValueChange = { viewModel.setPageInterval(it.roundToInt()) },
                        valueRange = 2f..20f,
                        steps = 17
                    )
                } else {
                    Text(scrollSpeedDescription(state.scrollSpeed))
                    Slider(
                        value = state.scrollSpeed.toFloat(),
                        onValueChange = { viewModel.setScrollSpeed(it.roundToInt()) },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                    Text(stringResource(R.string.reader_v2_speed_range), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.reader_v2_playback_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagerReader(state: ReaderUiState, onPageChanged: (Int) -> Unit, onPause: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = state.currentPage, pageCount = { state.pages.size })
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnPause by rememberUpdatedState(onPause)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { currentOnPageChanged(it) }
    }
    LaunchedEffect(state.isAutoPlaying, state.pageIntervalSeconds) {
        if (!state.isAutoPlaying) return@LaunchedEffect
        while (isActive) {
            if (pagerState.currentPage >= state.pages.lastIndex) {
                currentOnPause()
                break
            }
            delay(state.pageIntervalSeconds * 1000L)
            pagerState.animateScrollToPage(pagerState.currentPage + 1)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black).pauseOnReaderTouch(onPause)
    ) { page ->
        ComicPageImage(state.pages[page], Modifier.fillMaxSize())
    }
}

private data class ScrollZoomAnchor(val index: Int, val offset: Int)

@Composable
private fun ScrollReader(state: ReaderUiState, onPageChanged: (Int) -> Unit, onPause: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
    val density = LocalDensity.current
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    val currentOnPause by rememberUpdatedState(onPause)
    var scale by remember { mutableFloatStateOf(1f) }
    var horizontalOffset by remember { mutableFloatStateOf(0f) }
    var zoomAnchor by remember { mutableStateOf<ScrollZoomAnchor?>(null) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        // Save the top page, including when an unusually tall page fills the screen.
        snapshotFlow { listState.firstVisibleItemIndex }.collect { currentOnPageChanged(it) }
    }
    LaunchedEffect(zoomAnchor) {
        zoomAnchor?.let { listState.scrollToItem(it.index, it.offset) }
    }
    LaunchedEffect(state.isAutoPlaying, state.scrollSpeed, viewportHeight) {
        if (!state.isAutoPlaying || viewportHeight <= 0) return@LaunchedEffect
        val speed = ReaderPlaybackSpeeds.pixelsPerSecond(state.scrollSpeed, viewportHeight)
        val accumulator = ReaderScrollAccumulator()
        listState.scroll(MutatePriority.Default) {
            var previousFrame = withFrameNanos { it }
            while (isActive) {
                val frame = withFrameNanos { it }
                val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
                previousFrame = frame
                if (!listState.canScrollForward) {
                    currentOnPause()
                    break
                }
                val distance = accumulator.frameDistance(speed, seconds)
                if (distance > 0f) scrollBy(distance)
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height }.background(Color.Black).clipToBounds()
    ) {
        val contentWidth = maxWidth
        val viewportWidth = with(density) { maxWidth.toPx() }
        val maxOffset = ReaderZoomGeometry.horizontalPanLimit(viewportWidth, scale)
        // A size/orientation change may reduce the allowable horizontal range.
        val displayedOffset = horizontalOffset.coerceIn(-maxOffset, maxOffset)
        Box(
            Modifier.fillMaxSize().scrollReaderGestures(
                zoomed = scale > 1.001f,
                onTouch = onPause,
                onTransform = { zoom, pan, centroid ->
                    val result = ReaderZoomGeometry.transform(
                        viewportWidth, scale, displayedOffset, listState.firstVisibleItemScrollOffset.toFloat(),
                        zoom, pan.x, pan.y, centroid.x, centroid.y,
                        firstVisiblePageIndex = listState.firstVisibleItemIndex
                    )
                    horizontalOffset = result.horizontalOffset
                    if (result.scale != scale) {
                        zoomAnchor = ScrollZoomAnchor(
                            listState.firstVisibleItemIndex,
                            result.scrollOffset.roundToInt()
                        )
                        scale = result.scale
                    } else {
                        // While enlarged, both axes follow the same finger. At
                        // fit width or smaller, LazyColumn handles normal flings.
                        listState.dispatchRawDelta(-pan.y)
                    }
                }
            ),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                // Keep the full viewport touchable below 1x, including its black
                // margins. Only each centered page becomes narrower in that case.
                modifier = Modifier.fillMaxHeight().requiredWidth(contentWidth * maxOf(1f, scale))
                    .offset { IntOffset(displayedOffset.roundToInt(), 0) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(state.pages, key = { it.path }) { page ->
                    ComicPageImage(page, Modifier.width(contentWidth * scale).aspectRatio(page.aspectRatio))
                }
            }
        }
        if (scale != 1f) {
            FilledTonalButton(
                onClick = {
                    onPause()
                    val reset = ReaderZoomGeometry.reset(scale, listState.firstVisibleItemScrollOffset.toFloat())
                    zoomAnchor = ScrollZoomAnchor(
                        listState.firstVisibleItemIndex,
                        reset.scrollOffset.roundToInt()
                    )
                    scale = reset.scale
                    horizontalOffset = reset.horizontalOffset
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text(stringResource(R.string.reader_v2_reset_zoom, (scale * 100).roundToInt()))
            }
        }
    }
}

/** Observe touches in the initial pass without taking the pager's swipe gesture. */
@Composable
private fun Modifier.pauseOnReaderTouch(onTouch: () -> Unit): Modifier {
    val currentOnTouch by rememberUpdatedState(onTouch)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            currentOnTouch()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        }
    }
}

/** Two fingers zoom; at 1x or smaller, a single finger uses normal lazy-list scrolling. */
@Composable
private fun Modifier.scrollReaderGestures(
    zoomed: Boolean,
    onTouch: () -> Unit,
    onTransform: (zoom: Float, pan: Offset, centroid: Offset) -> Unit
): Modifier {
    val currentZoomed by rememberUpdatedState(zoomed)
    val currentOnTouch by rememberUpdatedState(onTouch)
    val currentOnTransform by rememberUpdatedState(onTransform)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            currentOnTouch()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    currentOnTransform(event.calculateZoom(), event.calculatePan(), event.calculateCentroid(useCurrent = false))
                    event.changes.forEach { it.consume() }
                } else if (currentZoomed && pressed == 1) {
                    val change = event.changes.first { it.pressed }
                    currentOnTransform(1f, change.position - change.previousPosition, change.position)
                    change.consume()
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
private fun ComicPageImage(page: ReaderPage, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(page.path).crossfade(true).build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.image_load_failed), color = Color.Gray, textAlign = TextAlign.Center)
            }
        }
    )
}
