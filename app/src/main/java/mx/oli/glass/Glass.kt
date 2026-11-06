package mx.oli.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    var art by mutableStateOf(ArtCatalog.all.first())
    var images by mutableStateOf<ArtImages?>(null)
    var screen by mutableStateOf(Size(1920f, 1080f))

    val tint get() = Color(art.tint)
    val accent get() = Color(art.accent)
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop> { error("Backdrop no disponible") }

private fun DrawScope.drawCover(img: ImageBitmap, screen: Size, k: Float, origin: Offset) {
    val s = max(screen.width / img.width, screen.height / img.height) * (1.04f + 0.06f * k)
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
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawFallback(art: Artwork, screen: Size, origin: Offset) {
    val brush = Brush.verticalGradient(
        0f to Color(art.sky1),
        0.52f to Color(art.sky2),
        0.524f to Color(art.ground),
        1f to Color(art.ground2),
        startY = -origin.y,
        endY = screen.height - origin.y,
    )
    drawRect(brush)
}

/** Dibuja la obra (nítida o desenfocada) en coordenadas de pantalla, desplazada por [origin]. */
fun DrawScope.paintArt(b: Backdrop, blurred: Boolean, origin: Offset = Offset.Zero) {
    val k = if (Config.DRIFT) b.progress.value else 0f
    val img = if (blurred) b.images?.blur else b.images?.sharp
    if (img != null) drawCover(img, b.screen, k, origin) else drawFallback(b.art, b.screen, origin)
}

/** Panel de vidrio: obra desenfocada detrás, tinte de la obra, brillo superior y borde de luz. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(36.dp),
    strength: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val b = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                paintArt(b, blurred = true, origin = origin)
                drawRect(Color.Black.copy(alpha = 0.18f))
                drawRect(
                    Brush.verticalGradient(
                        listOf(b.tint.copy(alpha = 0.22f * strength), b.tint.copy(alpha = 0.06f * strength))
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.20f * strength), Color.Transparent),
                        center = Offset(size.width * 0.18f, 0f),
                        radius = max(size.width, size.height) * 0.75f,
                    )
                )
            }
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))),
                shape,
            ),
        content = content,
    )
}
