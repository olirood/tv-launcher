package mx.oli.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
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

        /** Hora en que empieza el momento (para contar la rotación desde ahí). */
        fun startHour(m: Moment) = when (m) {
            AMANECER -> 5
            DIA -> 9
            ATARDECER -> 17
            NOCHE -> 20
        }
    }
}

/** Una estampa. [tint] y [accent] salen de la propia obra (los calcula el script de preparación). */
data class Artwork(
    val slug: String,
    val moment: Moment,
    val title: String,
    val artist: String,
    val year: String,
    val tint: Long,
    val accent: Long,
    val base: Long,
)

object ArtCatalog {
    val PLACEHOLDER = Artwork("", Moment.NOCHE, "", "", "", 0xFFC8D4E4, 0xFFC9D6EA, 0xFF0B0D11)

    @Volatile private var cache: List<Artwork>? = null

    /** Lee assets/art/catalog.json (lo genera el script que prepara las imágenes). */
    fun all(ctx: Context): List<Artwork> = cache ?: synchronized(this) {
        cache ?: runCatching {
            val text = ctx.assets.open("art/catalog.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Artwork(
                    slug = o.getString("slug"),
                    moment = Moment.valueOf(o.getString("moment")),
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    year = o.getString("year"),
                    tint = java.lang.Long.parseLong(o.getString("tint"), 16),
                    accent = java.lang.Long.parseLong(o.getString("accent"), 16),
                    base = java.lang.Long.parseLong(o.getString("base"), 16),
                )
            }
        }.getOrDefault(emptyList()).also { cache = it }
    }

    /**
     * La obra que toca ahora. Dentro de cada momento rota cada [rotateMin] minutos
     * (0 = una por día), arrancando en un punto distinto cada día. [skip] adelanta obras a mano.
     */
    fun pick(ctx: Context, now: Calendar, rotateMin: Int, skip: Int = 0): Artwork {
        val moment = Moment.forHour(now.get(Calendar.HOUR_OF_DAY))
        val pool = all(ctx).filter { it.moment == moment }.ifEmpty { all(ctx) }
        if (pool.isEmpty()) return PLACEHOLDER
        val day = now.get(Calendar.DAY_OF_YEAR)
        val slot = if (rotateMin <= 0) 0 else {
            val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val since = (minutes - Moment.startHour(moment) * 60 + 24 * 60) % (24 * 60)
            since / rotateMin
        }
        return pool[Math.floorMod(day * 5 + slot + skip, pool.size)]
    }
}

class ArtImages(val sharp: ImageBitmap?, val blur: ImageBitmap?)

object ArtLoader {
    /**
     * Carga la obra y su versión desenfocada desde assets/art.
     * Android 11 no tiene desenfoque en tiempo real, así que el vidrio usa esta versión pre-desenfocada.
     */
    fun load(ctx: Context, art: Artwork): ArtImages {
        if (art.slug.isEmpty()) return ArtImages(null, null)
        val sharp = decode(ctx, "art/${art.slug}.webp", 1920)
        val blur = decode(ctx, "art/${art.slug}_blur.webp", 480)
            ?: sharp?.let { Bitmap.createScaledBitmap(it, (it.width / 24).coerceAtLeast(8), (it.height / 24).coerceAtLeast(8), true) }
        return ArtImages(sharp?.asImageBitmap(), blur?.asImageBitmap())
    }

    fun decodeBitmap(ctx: Context, art: Artwork): Bitmap? =
        if (art.slug.isEmpty()) null else decode(ctx, "art/${art.slug}.webp", 1920)

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
