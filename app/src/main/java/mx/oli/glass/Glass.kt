package mx.oli.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * El fondo compartido: la obra actual, su versión desenfocada y el movimiento lento.
 * Los paneles de vidrio dibujan la parte de la versión desenfocada que queda detrás de ellos,
 * con la misma escala y desplazamiento que el fondo, así que el "vidrio" se mueve junto con la obra.
 */
@Stable
class Backdrop(val progress: State<Float>) {
    var art by mutableStateOf(ArtCatalog.PLACEHOLDER)
    var images by mutableStateOf<ArtImages?>(null)
    /** Obra anterior y avance del fundido (0 → 1) al cambiar de obra. */
    var prevImages by mutableStateOf<ArtImages?>(null)
    var fade by mutableFloatStateOf(1f)
    var screen by mutableStateOf(Size(1920f, 1080f))

    val tint get() = Color(art.tint)
    val accent get() = Color(art.accent)
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop> { error("Backdrop no disponible") }

private fun DrawScope.drawCover(img: ImageBitmap, screen: Size, k: Float, origin: Offset, alpha: Float = 1f) {
    // Sin movimiento la escala es exacta (1:1 cuando la imagen mide lo mismo que la pantalla): máxima nitidez.
    val s = max(screen.width / img.width, screen.height / img.height) * (if (Config.DRIFT) 1.04f + 0.06f * k else 1f)
    val w = img.width * s
    val h = img.height * s
    val left = (screen.width - w) / 2f - 0.015f * k * screen.width - origin.x
    val top = (screen.height - h) / 2f - 0.01f * k * screen.height - origin.y
    drawImage(
        image = img,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        filterQuality = FilterQuality.Medium,
        alpha = alpha,
    )
}

/** Dibuja la obra (nítida o desenfocada) en coordenadas de pantalla, desplazada por [origin]. */
fun DrawScope.paintArt(b: Backdrop, blurred: Boolean, origin: Offset = Offset.Zero) {
    val k = if (Config.DRIFT) b.progress.value else 0f
    val fade = b.fade
    val img = if (blurred) b.images?.blur else b.images?.sharp
    val prev = if (fade < 1f) (if (blurred) b.prevImages?.blur else b.prevImages?.sharp) else null
    if (prev != null) drawCover(prev, b.screen, k, origin) else if (img == null || fade < 1f) drawRect(Color(b.art.base))
    if (img != null) drawCover(img, b.screen, k, origin, if (prev != null) fade else 1f)
}

/** Panel de vidrio: obra desenfocada detrás, tinte de la obra, brillo superior y borde de luz. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(36.dp),
    strength: Float = 1f,
    dim: Float = 0.46f,
    /** false: sin obra detrás (para mosaicos que el banner cubre por completo; ahorra trabajo a la GPU). */
    backdrop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val b = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.clip(shape)) {
        // El fondo del vidrio va en su propia capa fuera de pantalla: la GPU lo pinta una vez
        // y luego solo reutiliza la textura. En esta tele eso es la diferencia entre ~17 y 60 cuadros.
        Box(
            Modifier
                .matchParentSize()
                .then(if (backdrop) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    // Vidrio oscuro: la obra desenfocada, bastante oscurecida, con un leve tinte y un brillo superior sutil.
                    if (backdrop) paintArt(b, blurred = true, origin = origin) else drawRect(Color(0xFF1A1C21))
                    drawRect(Color(0xFF0B0C10).copy(alpha = dim))
                    drawRect(
                        Brush.verticalGradient(
                            listOf(b.tint.copy(alpha = 0.10f * strength), b.tint.copy(alpha = 0.02f * strength))
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.10f * strength), Color.Transparent),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = max(size.width, size.height) * 0.7f,
                        )
                    )
                }
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.04f))),
                    shape,
                ),
        )
        content()
    }
}
