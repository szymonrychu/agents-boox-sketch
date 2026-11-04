package io.github.szymonrychu.booxsketch.documents

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val TAG_SOURCES_CLOSE = "sources_close"
const val TAG_SOURCES_EMPTY = "sources_empty"
const val TAG_SOURCES_LOADING = "sources_loading"

fun sourceTag(sketchId: String) = "source_$sketchId"

/** Sketches (and notes) a spec version was made from; thumbnails are small and nothing scrolls. */
@Composable
fun SourcesScreen(
    items: List<SourceItem>?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Sources", Modifier.weight(1f))
            TextButton(
                onClick = onClose,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(TAG_SOURCES_CLOSE),
            ) { Text("Close") }
        }
        when {
            items == null -> Text("Loading", Modifier.testTag(TAG_SOURCES_LOADING))
            items.isEmpty() -> Text("No sources for this version", Modifier.testTag(TAG_SOURCES_EMPTY))
            else -> items.take(MAX_ROWS).forEach { SourceRow(it) }
        }
    }
}

@Composable
private fun SourceRow(item: SourceItem) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, Color.Black))
            .padding(8.dp)
            .testTag(sourceTag(item.sketchId)),
    ) {
        Text(if (item.kind == SourceKind.NOTES) "Handwritten notes" else "Drawing")
        if (item.state == SourceState.GONE) {
            Text("no longer stored")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (file in item.thumbs.take(MAX_THUMBS)) {
                    val bitmap = remember(file) { BitmapFactory.decodeFile(file.path) }
                    if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.height(THUMB_HEIGHT.dp))
                }
            }
            if (item.state == SourceState.REMOTE_PAGE0) Text("first page only")
        }
    }
}

private const val MAX_ROWS = 10
private const val MAX_THUMBS = 4
private const val THUMB_HEIGHT = 160
