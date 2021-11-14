package info.nightscout.comboctl.comboandroid.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SimpleGrid(
    modifier: Modifier = Modifier,
    columns: Int = 2,
    spacing: Dp = 0.dp,
    content: @Composable () -> Unit,

    ) {
    Layout(
        modifier = modifier,
        content = content
    ) { measurables, constraints ->
        val elementWidth = measurables.maxOf { measurable ->
            measurable.maxIntrinsicWidth(constraints.minHeight)
        }
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints = constraints.copy(minWidth = elementWidth, maxWidth = elementWidth, minHeight = 0))
        }
        val rows: List<List<Placeable>> = placeables.chunked(columns)

        val spacingPx = spacing.roundToPx()
        val height = rows.sumOf { placeables.maxOf { it.height } } + (rows.size - 1) * spacingPx
        val width = columns * elementWidth + (columns - 1) * spacingPx

        layout(width = width, height = height) {
            var y = 0
            rows.forEach { row ->
                var x = 0
                row.forEach {
                    it.placeRelative(x = x, y = y)
                    x += it.width + spacingPx
                }
                y += row.maxOf { it.height } + spacingPx
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
fun SimpleGridPreview() {
    SimpleGrid(columns = 2, modifier = Modifier
        .fillMaxWidth(1f)
        .wrapContentSize(align = Alignment.Center), spacing = 4.dp) {
        val list = listOf("first with long text", "second", "third")
        list.forEach { Text(it, color = Color.Green, modifier = Modifier.background(Color.Blue)) }
    }
}