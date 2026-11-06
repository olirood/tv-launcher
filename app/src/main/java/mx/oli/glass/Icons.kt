package mx.oli.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Íconos de trazo simples, dibujados en una cuadrícula de 24x24. */
enum class Ico { SEARCH, SLIDERS, HDMI, GRID, SUN, MOON, CLOUD, RAIN, IMAGE, SPEAKER, BT, GEAR }

@Composable
fun Icon(kind: Ico, color: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 1.9f * u, cap = StrokeCap.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = st.width, cap = StrokeCap.Round)
        when (kind) {
            Ico.SEARCH -> {
                drawCircle(color, 7f * u, p(11f, 11f), style = st)
                line(16.5f, 16.5f, 20.5f, 20.5f)
            }
            Ico.SLIDERS -> {
                line(4f, 7f, 14f, 7f); line(18f, 7f, 20f, 7f)
                line(4f, 17f, 8f, 17f); line(12f, 17f, 20f, 17f)
                drawCircle(color, 2f * u, p(16f, 7f), style = st)
                drawCircle(color, 2f * u, p(10f, 17f), style = st)
            }
            Ico.HDMI -> {
                drawRoundRect(color, p(3f, 7f), Size(18f * u, 10f * u), CornerRadius(2f * u), style = st)
                line(7f, 11f, 9f, 11f); line(15f, 11f, 17f, 11f)
            }
            Ico.GRID -> {
                for ((x, y) in listOf(3.5f to 3.5f, 13.5f to 3.5f, 3.5f to 13.5f, 13.5f to 13.5f)) {
                    drawRoundRect(color, p(x, y), Size(7f * u, 7f * u), CornerRadius(2f * u), style = st)
                }
            }
            Ico.SUN -> {
                drawCircle(color, 4.5f * u, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
                }
            }
            Ico.MOON -> {
                val path = Path().apply {
                    moveTo(20f * u, 14.5f * u)
                    cubicTo(18.5f * u, 19f * u, 13f * u, 21.5f * u, 8.5f * u, 19.5f * u)
                    cubicTo(4f * u, 17.5f * u, 2.5f * u, 12f * u, 4.5f * u, 8f * u)
                    cubicTo(5.8f * u, 5.6f * u, 7.4f * u, 4.5f * u, 9.5f * u, 4f * u)
                    cubicTo(7.8f * u, 8.5f * u, 10f * u, 13.5f * u, 14.5f * u, 15f * u)
                    cubicTo(16.4f * u, 15.6f * u, 18.3f * u, 15.4f * u, 20f * u, 14.5f * u)
                    close()
                }
                drawPath(path, color, style = st)
            }
            Ico.IMAGE -> {
                drawRoundRect(color, p(3f, 4.5f), Size(18f * u, 15f * u), CornerRadius(2.5f * u), style = st)
                line(3.5f, 17f, 9f, 11.5f); line(9f, 11.5f, 13f, 15.5f); line(12f, 14.5f, 15f, 11.5f); line(15f, 11.5f, 20.5f, 17f)
                drawCircle(color, 1.6f * u, p(15.5f, 8.5f), style = st)
            }
            Ico.SPEAKER -> {
                val path = Path().apply {
                    moveTo(4f * u, 9.5f * u); lineTo(8f * u, 9.5f * u); lineTo(12.5f * u, 5.5f * u)
                    lineTo(12.5f * u, 18.5f * u); lineTo(8f * u, 14.5f * u); lineTo(4f * u, 14.5f * u); close()
                }
                drawPath(path, color, style = st)
                drawArc(color, -45f, 90f, false, p(12f, 8f), Size(6f * u, 8f * u), style = st)
                drawArc(color, -50f, 100f, false, p(11f, 4.5f), Size(10f * u, 15f * u), style = st)
            }
            Ico.BT -> {
                line(7f, 7.5f, 16.5f, 16f); line(16.5f, 16f, 12f, 20.5f); line(12f, 20.5f, 12f, 3.5f)
                line(12f, 3.5f, 16.5f, 8f); line(16.5f, 8f, 7f, 16.5f)
            }
            Ico.GEAR -> {
                drawCircle(color, 3.2f * u, p(12f, 12f), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 6.2f * cos(a).toFloat(), 12f + 6.2f * sin(a).toFloat(), 12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat())
                }
                drawCircle(color, 6.2f * u, p(12f, 12f), style = st)
            }
            Ico.CLOUD, Ico.RAIN -> {
                cloud(color, u, st)
                if (kind == Ico.RAIN) {
                    line(8f, 19f, 7f, 22f); line(12f, 19f, 11f, 22f); line(16f, 19f, 15f, 22f)
                }
            }
        }
    }
}

private fun DrawScope.cloud(color: Color, u: Float, st: Stroke) {
    val path = Path().apply {
        moveTo(7f * u, 17f * u)
        cubicTo(4f * u, 17f * u, 2.5f * u, 15f * u, 2.5f * u, 13f * u)
        cubicTo(2.5f * u, 10.5f * u, 4.5f * u, 9f * u, 6.5f * u, 9.2f * u)
        cubicTo(7.3f * u, 6.3f * u, 9.7f * u, 4.5f * u, 12.5f * u, 4.5f * u)
        cubicTo(16f * u, 4.5f * u, 18.7f * u, 7.2f * u, 18.8f * u, 10.4f * u)
        cubicTo(20.6f * u, 10.7f * u, 21.8f * u, 12.2f * u, 21.8f * u, 13.9f * u)
        cubicTo(21.8f * u, 15.6f * u, 20.4f * u, 17f * u, 18.5f * u, 17f * u)
        close()
    }
    drawPath(path, color, style = st)
}
