package info.nightscout.comboctl.comboandroid.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import info.nightscout.comboctl.base.DISPLAY_FRAME_HEIGHT
import info.nightscout.comboctl.base.DISPLAY_FRAME_WIDTH
import info.nightscout.comboctl.base.DisplayFrame

@Composable
fun ComboDisplay(frame: DisplayFrame?) {
    ComboDisplayLayout {
        frame?.forEach {
            Box(Modifier.background(if (it) Color.Green else Color.DarkGray))
        }
    }
}

@Composable
private fun ComboDisplayLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = content
    ) { measurables, constraints ->
        val blockSize = constraints.maxWidth / DISPLAY_FRAME_WIDTH
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(maxHeight = blockSize, minHeight = blockSize, maxWidth = blockSize, minWidth = blockSize))
        }
        layout(width = blockSize * DISPLAY_FRAME_WIDTH, height = blockSize * DISPLAY_FRAME_HEIGHT) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(x = index.mod(DISPLAY_FRAME_WIDTH) * blockSize, y = index / DISPLAY_FRAME_WIDTH * blockSize)
            }
        }
    }
}