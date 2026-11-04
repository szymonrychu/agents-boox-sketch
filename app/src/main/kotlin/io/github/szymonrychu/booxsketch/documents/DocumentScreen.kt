package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.szymonrychu.booxsketch.board.BoardGeometry
import io.github.szymonrychu.booxsketch.data.FeedbackEntity
import io.github.szymonrychu.booxsketch.model.Stroke
import io.github.szymonrychu.booxsketch.onyx.OnyxPenRenderer
import io.github.szymonrychu.booxsketch.render.CanvasStrokeRenderer
import io.github.szymonrychu.booxsketch.model.Canvas as PageCanvas

const val TAG_DOC_CLOSE = "doc_close"
const val TAG_DOC_VERSION = "doc_version"
const val TAG_DOC_SHARE = "doc_share"
const val TAG_DOC_SOURCES = "doc_sources"
const val TAG_DOC_COUNTER = "doc_counter"
const val TAG_DOC_PREV = "doc_prev"
const val TAG_DOC_NEXT = "doc_next"
const val TAG_DOC_LOADING = "doc_loading"
const val TAG_DOC_READ_ONLY = "doc_read_only"

fun docVersionTag(v: Int) = "doc_version_$v"

/** The counter text, "2/7". */
fun pageCounter(state: DocumentViewState) = "${state.pageIndex + 1}/${state.pages.size.coerceAtLeast(1)}"

/**
 * The document view: header (Close, title, version picker, Share, Sources), the page in [pageArea] and the footer with
 * Prev, counter and Next. Nothing scrolls; the page is fitted and centred, so landscape scales it to the height.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun DocumentScreen(
    title: String,
    state: DocumentViewState,
    versions: List<Int>,
    onClose: () -> Unit,
    onSelectVersion: (Int) -> Unit,
    onShare: () -> Unit,
    onSources: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    feedback: List<FeedbackEntity> = emptyList(),
    pageArea: @Composable (
        Modifier,
    ) -> Unit = { m ->
        DocumentPageView(
            state.pages
                .getOrNull(state.pageIndex)
                ?.file
                ?.path,
            emptyList(),
            m,
        )
    },
) {
    var picker by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_CLOSE)) { Text("Close") }
            Text(title, Modifier.weight(1f))
            Box {
                TextButton(onClick = { picker = true }, modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_VERSION)) {
                    Text("v${state.version}")
                }
                DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                    for (v in versions) {
                        DropdownMenuItem(
                            text = { Text("v$v" + if (v == state.latestVersion) " (latest)" else "") },
                            onClick = {
                                picker = false
                                onSelectVersion(v)
                            },
                            modifier =
                                Modifier
                                    .testTag(docVersionTag(v))
                                    .then(if (v == state.version) Modifier.border(BorderStroke(3.dp, Color.Black)) else Modifier),
                        )
                    }
                }
            }
            TextButton(
                onClick = onShare,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_SHARE),
            ) { Text("Share .md") }
            TextButton(
                onClick = onSources,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_SOURCES),
            ) { Text("Sources") }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.loaded) pageArea(Modifier.fillMaxSize()) else Text("Loading", Modifier.testTag(TAG_DOC_LOADING))
            if (notes && feedback.isNotEmpty()) FeedbackPanel(feedback, onClose = { notes = false })
        }
        if (!notes && feedback.isNotEmpty()) {
            FeedbackLatest(feedback.first(), feedback.size, onOpen = { notes = true }, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onPrev,
                enabled = state.pageIndex > 0,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_PREV),
            ) { Text("Prev") }
            Text(pageCounter(state), Modifier.testTag(TAG_DOC_COUNTER))
            TextButton(
                onClick = onNext,
                enabled = state.pageIndex < state.pages.size - 1,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_NEXT),
            ) { Text("Next") }
            if (feedback.isNotEmpty()) {
                TextButton(
                    onClick = { notes = !notes },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_DOC_FEEDBACK_TOGGLE),
                ) { Text("Notes (${feedback.size})") }
            }
            if (state.readOnly) Text("Read only", Modifier.padding(start = 12.dp).testTag(TAG_DOC_READ_ONLY))
        }
    }
}

/** A frozen page bitmap with saved [strokes] over it, fitted and centred. Finger swipes call [onSwipe]. */
@Composable
fun DocumentPageView(
    pagePath: String?,
    strokes: List<Stroke>,
    modifier: Modifier = Modifier,
    onSwipe: (SwipeDir) -> Unit = {},
) {
    val bitmap: Bitmap? = remember(pagePath) { pagePath?.let { BitmapFactory.decodeFile(it) } }
    val swipe = ThresholdPageSwipe(with(LocalDensity.current) { ThresholdPageSwipe.DEFAULT_MIN_TRAVEL_PX.dp.toPx() })
    val renderer = remember { CanvasStrokeRenderer(OnyxPenRenderer.onDevice()) }
    Canvas(modifier.background(Color.White).fingerSwipes(swipe, onSwipe)) {
        val b = bitmap ?: return@Canvas
        val g = BoardGeometry(size.width.toInt(), size.height.toInt(), PageCanvas(b.width, b.height, 1f))
        drawIntoCanvas {
            val c = it.nativeCanvas
            c.save()
            c.translate(g.dx, g.dy)
            c.scale(g.scale, g.scale)
            c.drawBitmap(b, 0f, 0f, null)
            c.restore()
            renderer.draw(c, strokes, g.scale, g.dx, g.dy)
        }
    }
}

/** Reports finger (touch, never pen) swipes classified by [swipe]. */
private fun Modifier.fingerSwipes(
    swipe: PageSwipe,
    onSwipe: (SwipeDir) -> Unit,
): Modifier =
    pointerInput(swipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            var last = down
            var tracking = true
            while (tracking) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change != null) last = change
                tracking = change != null && !change.changedToUp()
            }
            val dir =
                swipe.classify(
                    last.position.x - down.position.x,
                    last.position.y - down.position.y,
                    last.uptimeMillis - down.uptimeMillis,
                )
            if (dir != SwipeDir.NONE) onSwipe(dir)
        }
    }
