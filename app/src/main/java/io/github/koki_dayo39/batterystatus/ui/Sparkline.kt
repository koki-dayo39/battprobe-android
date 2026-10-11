package io.github.koki_dayo39.batterystatus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal line chart. [values] are drawn left-to-right across a fixed window of
 * [capacity] samples so the line scrolls in from the left as data arrives.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    capacity: Int,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Green,
    zeroLineColor: Color = Color.Gray,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxV = max(values.max(), 0f)
        val minV = min(values.min(), 0f)
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (capacity - 1).coerceAtLeast(1)
        val padding = 2.dp.toPx()
        val usableHeight = size.height - padding * 2

        fun y(v: Float) = padding + usableHeight - (v - minV) / range * usableHeight

        val zeroY = y(0f)
        drawLine(zeroLineColor, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())

        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(
            path,
            lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
