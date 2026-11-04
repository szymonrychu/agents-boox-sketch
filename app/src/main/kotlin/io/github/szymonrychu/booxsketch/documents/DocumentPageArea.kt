package io.github.szymonrychu.booxsketch.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.szymonrychu.booxsketch.board.BoardScreen
import io.github.szymonrychu.booxsketch.board.ToolbarOptions
import io.github.szymonrychu.booxsketch.ink.StrokeInput
import io.github.szymonrychu.booxsketch.model.Canvas
import kotlinx.coroutines.launch

/**
 * The page of a document: the latest unsent version is inkable (pen always inks, a finger swipe turns pages, the
 * toolbar has no page strip and sends notes); every other version shows its page and saved ink and starts no input.
 */
@Suppress("LongParameterList")
@Composable
fun DocumentPageArea(
    conversationLocalId: String,
    state: DocumentViewState,
    layout: DocumentLayout,
    ink: DocumentInk,
    input: StrokeInput,
    turner: PageTurner,
    onFingerSwipe: (((SwipeDir) -> Unit)?) -> Unit,
    onSendNotes: (caption: String?) -> Unit,
    modifier: Modifier = Modifier,
    onReadOnlySwipe: (SwipeDir) -> Unit = {},
) {
    val page = state.pages.getOrNull(state.pageIndex) ?: return
    if (state.readOnly) {
        val strokes =
            remember(conversationLocalId, state.version, state.pageIndex) {
                ink
                    .saved(conversationLocalId, state.version)
                    ?.pages
                    ?.getOrNull(state.pageIndex)
                    ?.strokes
                    .orEmpty()
            }
        DocumentPageView(page.file.path, strokes, modifier, onSwipe = onReadOnlySwipe)
        return
    }
    val scope = rememberCoroutineScope()
    val board =
        remember(conversationLocalId, state.version) {
            ink.board(conversationLocalId, state.version, state.pages.size, Canvas(layout.widthPx, layout.pageHeightPx, DPI))
        }
    val background: Bitmap? = remember(page.file.path) { BitmapFactory.decodeFile(page.file.path) }
    LaunchedEffect(state.pageIndex) { board.selectPage(state.pageIndex) }
    DisposableEffect(turner) {
        onFingerSwipe { dir ->
            when (dir) {
                SwipeDir.NEXT -> scope.launch { turner.turn(1) }
                SwipeDir.PREV -> scope.launch { turner.turn(-1) }
                SwipeDir.NONE -> Unit
            }
        }
        onDispose { onFingerSwipe(null) }
    }
    BoardScreen(
        vm = board,
        input = input,
        modifier = modifier,
        onSend = { _, caption -> onSendNotes(caption) },
        background = background,
        toolbar = ToolbarOptions(pageControls = false, sendLabel = "Send notes"),
    )
}

/** Waits until a page change was composed and drawn: two frames. */
suspend fun awaitFrames() {
    withFrameNanos { }
    withFrameNanos { }
}

private const val DPI = 300f
