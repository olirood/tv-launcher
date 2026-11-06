package mx.oli.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Calendar

enum class Moment(val label: String, val seal: String) {
    AMANECER("Amanecer", "朝"),
    DIA("Día", "昼"),
    ATARDECER("Atardecer", "夕"),
    NOCHE("Noche", "夜");

    companion object {
        fun forHour(hour: Int): Moment = when (hour) {
            in 5..8 -> AMANECER
            in 9..16 -> DIA
            in 17..19 -> ATARDECER
            else -> NOCHE
        }
    }
}

/**
 * Una estampa. Los colores son la paleta de la obra: se usan para teñir el vidrio,
 * para el acento y como respaldo si la imagen todavía no está en assets/art.
 */
data class Artwork(
    val slug: String,
    val moment: Moment,
    val title: String,
    val artist: String,
    val year: String,
    val series: String,
    val sky1: Long,
    val sky2: Long,
    val ground: Long,
    val ground2: Long,
    val tint: Long,
    val accent: Long,
)

object ArtCatalog {
    private const val S36 = "Treinta y seis vistas del monte Fuji"
    private const val TOK = "Cincuenta y tres estaciones del Tōkaidō"
    private const val EDO = "Cien vistas famosas de Edo"
    private const val HOK = "Katsushika Hokusai"
    private const val HIR = "Utagawa Hiroshige"

    val all = listOf(
        Artwork("amanecer-1-fuji-rojo", Moment.AMANECER, "Fuji rojo — Viento del sur, cielo despejado", HOK, "c. 1830–1832", S36,
            0xFF2C6A8A, 0xFFA9CBCF, 0xFF9A3A22, 0xFF5E2416, 0xFFEED6BE, 0xFFF2A37C),
        Artwork("amanecer-2-isawa", Moment.AMANECER, "Amanecer en Isawa, provincia de Kai", HOK, "c. 1830–1832", S36,
            0xFF3B6E8C, 0xFFE9D9B8, 0xFF4B6B5A, 0xFF2A3D33, 0xFFEEE2C8, 0xFFF0C08A),
        Artwork("amanecer-3-nihonbashi", Moment.AMANECER, "Nihonbashi: vista matutina", HIR, "1833–1834", TOK,
            0xFFC96A3A, 0xFFF1D7B0, 0xFF6B5A45, 0xFF3A2E22, 0xFFF4DEC4, 0xFFF4B07C),

        Artwork("dia-1-gran-ola", Moment.DIA, "La gran ola de Kanagawa", HOK, "c. 1830–1832", S36,
            0xFFCFC2A2, 0xFFE6DCC4, 0xFF24466F, 0xFF132A46, 0xFFE8E2D2, 0xFFA9C8EA),
        Artwork("dia-2-kajikazawa", Moment.DIA, "Kajikazawa, provincia de Kai", HOK, "c. 1830–1832", S36,
            0xFFE6DCC2, 0xFFD9CFB5, 0xFF2B4F7A, 0xFF15304F, 0xFFE6E2D4, 0xFF9FC2E8),
        Artwork("dia-3-ejiri", Moment.DIA, "Ejiri, provincia de Suruga", HOK, "c. 1830–1832", S36,
            0xFFB7CFD8, 0xFFE7E1CF, 0xFF8C9A6A, 0xFF4F5B3B, 0xFFE4E6D4, 0xFFC6D79A),

        // "Jardín de ciruelos en Kameido" queda fuera hasta conseguir la imagen.
        Artwork("atardecer-2-seta", Moment.ATARDECER, "Atardecer en Seta", HIR, "c. 1834", "Ocho vistas de Ōmi",
            0xFFD9653B, 0xFFF2C38A, 0xFF355F7A, 0xFF1D3446, 0xFFF8DCC4, 0xFFF7B47E),
        Artwork("atardecer-3-ohashi", Moment.ATARDECER, "Aguacero sobre el puente Ōhashi y Atake", HIR, "1857", EDO,
            0xFF2B3440, 0xFF7F8B93, 0xFF3F6A8A, 0xFF21394C, 0xFFD2DAE2, 0xFFB7C9DA),

        Artwork("noche-1-kanbara", Moment.NOCHE, "Kanbara: nieve nocturna", HIR, "1833–1834", TOK,
            0xFF101318, 0xFF363E4A, 0xFFBFC4C8, 0xFF8E959C, 0xFFC8D4E4, 0xFFC9D6EA),
        Artwork("noche-2-ryogoku", Moment.NOCHE, "Fuegos artificiales en Ryōgoku", HIR, "1858", EDO,
            0xFF0B1220, 0xFF22324A, 0xFF1A2A3C, 0xFF0C1622, 0xFFC8D2E6, 0xFFF2D27A),
        Artwork("noche-3-oji", Moment.NOCHE, "Zorros de fuego en Ōji, víspera de Año Nuevo", HIR, "1857", EDO,
            0xFF0E1626, 0xFF2B3A55, 0xFF3D4A3A, 0xFF1F261D, 0xFFCED4E2, 0xFFF0D9A0),
    )

    /** La obra de hoy para el momento actual: rota una vez al día dentro de cada momento. */
    fun pick(now: Calendar): Artwork {
        val moment = Moment.forHour(now.get(Calendar.HOUR_OF_DAY))
        val pool = all.filter { it.moment == moment }
        return pool[now.get(Calendar.DAY_OF_YEAR) % pool.size]
    }
}

class ArtImages(val sharp: ImageBitmap?, val blur: ImageBitmap?)

object ArtLoader {
    /**
     * Carga la obra y su versión desenfocada desde assets/art.
     * Android 11 no tiene desenfoque en tiempo real, así que el vidrio usa esta versión
     * pre-desenfocada (o una reducida al vuelo si no existe).
     */
    fun load(ctx: Context, art: Artwork): ArtImages {
        val sharp = decode(ctx, "art/${art.slug}.webp", 1920)
        val blur = decode(ctx, "art/${art.slug}_blur.webp", 480)
            ?: sharp?.let { Bitmap.createScaledBitmap(it, (it.width / 24).coerceAtLeast(8), (it.height / 24).coerceAtLeast(8), true) }
        return ArtImages(sharp?.asImageBitmap(), blur?.asImageBitmap())
    }

    fun decodeBitmap(ctx: Context, art: Artwork): Bitmap? = decode(ctx, "art/${art.slug}.webp", 1920)

    private fun decode(ctx: Context, path: String, maxWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    }
}
