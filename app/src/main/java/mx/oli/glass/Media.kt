package mx.oli.glass

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Un título para las filas de "Continuar viendo" y "Nuevo en tus apps". */
data class MediaItem(
    val key: String,
    val title: String,
    /** "Netflix · Serie · 2026" */
    val meta: String,
    val overview: String,
    val card: ImageBitmap?,
    /** Imagen grande para el fondo (URL de TMDB o del proveedor). */
    val backdropUrl: String?,
    val logoUrl: String?,
    val intent: Intent?,
    val progress: Float? = null,
)

/** Descarga con caché en disco (las imágenes y respuestas de TMDB no cambian seguido). */
object Net {
    private fun cacheFile(ctx: Context, url: String, dir: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, dir).apply { mkdirs() }, h)
    }

    fun get(ctx: Context, url: String, maxAgeMs: Long, auth: String? = null): ByteArray? {
        val f = cacheFile(ctx, url, "net")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return f.readBytes()
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                if (auth != null) setRequestProperty("Authorization", auth)
                setRequestProperty("Accept", "application/json")
            }
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            f.writeBytes(bytes)
            bytes
        } catch (e: Exception) {
            if (f.exists()) f.readBytes() else null
        }
    }

    fun bitmap(ctx: Context, url: String?, maxWidth: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val bytes = if (url.startsWith("content:") || url.startsWith("android.resource:")) {
                ctx.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
            } else {
                get(ctx, url, 14L * 24 * 3600 * 1000)
            } ?: return null
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            var s = 1
            while (b.outWidth / (s * 2) >= maxWidth) s *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                ?: return null
            if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true) else bmp
        } catch (e: Exception) {
            null
        }
    }
}

// ---------- Continuar viendo (registro "Watch Next" del sistema) ----------

object WatchNextRepo {
    private val URI = Uri.parse("content://android.media.tv/watch_next_program")

    /** Devuelve lo que las apps publicaron como "seguir viendo". Si Android no nos deja leerlo, regresa vacío. */
    fun load(ctx: Context, appLabels: Map<String, String>): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        try {
            ctx.contentResolver.query(URI, null, null, null, "last_engagement_time_utc_millis DESC")?.use { c ->
                fun col(n: String) = c.getColumnIndex(n)
                while (c.moveToNext() && out.size < 12) {
                    val pkg = c.getString(col("package_name")) ?: ""
                    val title = c.getString(col("title")) ?: continue
                    val episode = col("episode_title").takeIf { it >= 0 }?.let { c.getString(it) }
                    val poster = c.getString(col("poster_art_uri"))
                    val thumb = col("thumbnail_uri").takeIf { it >= 0 }?.let { c.getString(it) }
                    val intentUri = c.getString(col("intent_uri"))
                    val dur = col("duration_millis").takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                    val pos = col("last_playback_position_millis").takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                    val intent = intentUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val app = appLabels[pkg] ?: ""
                    out += MediaItem(
                        key = "wn:$pkg:$title:${episode ?: ""}",
                        title = title,
                        meta = listOfNotNull(app.takeIf { it.isNotEmpty() }, episode).joinToString(" · "),
                        overview = "",
                        card = Net.bitmap(ctx, thumb ?: poster, 384)?.asImageBitmap(),
                        backdropUrl = poster ?: thumb,
                        logoUrl = null,
                        intent = intent,
                        progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null,
                    )
                }
                Log.i("Glass", "watch_next: ${c.count} filas visibles")
            } ?: Log.i("Glass", "watch_next: sin acceso (query nula)")
        } catch (e: Exception) {
            Log.i("Glass", "watch_next: sin acceso (${e.javaClass.simpleName}: ${e.message})")
        }
        return out
    }
}

// ---------- Nuevo en tus apps (TMDB, catálogo de México) ----------

object TmdbRepo {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p"

    /** Plataformas que tienes en el dock, por nombre de proveedor en TMDB → paquete de la app. */
    private val PROVIDERS = listOf(
        "Netflix" to "com.netflix.ninja",
        "Amazon Prime Video" to "com.amazon.amazonvideo.livingroom",
        "Max" to "com.wbd.stream",
        "HBO Max" to "com.wbd.stream",
        "Apple TV" to "com.apple.atve.androidtv.appletv",
        "Apple TV Plus" to "com.apple.atve.androidtv.appletv",
        "Apple TV+" to "com.apple.atve.androidtv.appletv",
    )

    private fun token(ctx: Context): String? =
        runCatching { ctx.assets.open("tmdb.key").bufferedReader().use { it.readText().trim() } }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun call(ctx: Context, path: String, maxAgeMs: Long = 6 * 3600 * 1000L): JSONObject? {
        val t = token(ctx) ?: return null
        val bearer = t.length > 40
        val url = API + path + (if (bearer) "" else (if ('?' in path) "&" else "?") + "api_key=$t")
        val bytes = Net.get(ctx, url, maxAgeMs, if (bearer) "Bearer $t" else null) ?: return null
        return runCatching { JSONObject(String(bytes)) }.getOrNull()
    }

    fun load(ctx: Context, appLabels: Map<String, String>): List<MediaItem> {
        if (token(ctx) == null) return emptyList()
        // IDs de proveedores en México, por nombre (los IDs cambian con los años; los nombres no tanto).
        val provs = mutableMapOf<Int, Pair<String, String>>() // id → (nombre, paquete)
        for (kind in listOf("movie", "tv")) {
            val arr = call(ctx, "/watch/providers/$kind?watch_region=MX&language=es-MX", 7 * 24 * 3600 * 1000L)
                ?.optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("provider_name")
                PROVIDERS.firstOrNull { it.first.equals(name, true) }?.let { provs[o.getInt("provider_id")] = name to it.second }
            }
        }
        // Solo las plataformas que sí tienes instaladas.
        val usable = provs.filterValues { appLabels.containsKey(it.second) }
        if (usable.isEmpty()) return emptyList()
        val ids = usable.keys.joinToString("|")

        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val since = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -75) }.let { df.format(it.time) }
        val today = df.format(Calendar.getInstance().time)
        val common = "watch_region=MX&with_watch_providers=$ids&with_watch_monetization_types=flatrate&language=es-MX&sort_by=popularity.desc"
        val movies = call(ctx, "/discover/movie?$common&primary_release_date.gte=$since&primary_release_date.lte=$today")
            ?.optJSONArray("results") ?: JSONArray()
        val shows = call(ctx, "/discover/tv?$common&air_date.gte=$since&air_date.lte=$today")
            ?.optJSONArray("results") ?: JSONArray()

        // Intercala series y películas, las más populares primero.
        val picked = mutableListOf<Pair<String, JSONObject>>()
        for (i in 0 until maxOf(movies.length(), shows.length())) {
            if (i < shows.length()) picked += "tv" to shows.getJSONObject(i)
            if (i < movies.length()) picked += "movie" to movies.getJSONObject(i)
        }

        val out = mutableListOf<MediaItem>()
        for ((kind, o) in picked) {
            if (out.size >= 14) break
            val backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val id = o.getInt("id")
            val detail = call(ctx, "/$kind/$id?language=es-MX&append_to_response=watch/providers,images&include_image_language=es,en,null",
                24 * 3600 * 1000L) ?: continue
            val mx = detail.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("MX")
            val flat = mx?.optJSONArray("flatrate") ?: continue
            var prov: Pair<String, String>? = null
            for (i in 0 until flat.length()) {
                prov = usable[flat.getJSONObject(i).getInt("provider_id")]
                if (prov != null) break
            }
            prov ?: continue
            val title = detail.optString(if (kind == "tv") "name" else "title")
            val date = detail.optString(if (kind == "tv") "first_air_date" else "release_date").take(4)
            val logos = detail.optJSONObject("images")?.optJSONArray("logos")
            var logo: String? = null
            if (logos != null) {
                for (lang in listOf("es", "en", "null")) {
                    for (i in 0 until logos.length()) {
                        val l = logos.getJSONObject(i)
                        if (l.optString("iso_639_1", "null") == lang && l.optString("file_path").endsWith(".png")) {
                            logo = "$IMG/w500${l.getString("file_path")}"; break
                        }
                    }
                    if (logo != null) break
                }
            }
            val appName = appLabels[prov.second] ?: prov.first
            out += MediaItem(
                key = "tmdb:$kind:$id",
                title = title,
                meta = listOf(appName, if (kind == "tv") "Serie" else "Película", date).filter { it.isNotBlank() }.joinToString(" · "),
                overview = detail.optString("overview").let { if (it.length > 220) it.take(217).substringBeforeLast(' ') + "…" else it },
                card = Net.bitmap(ctx, "$IMG/w780$backdrop", 384)?.asImageBitmap(),
                backdropUrl = "$IMG/w1280$backdrop",
                logoUrl = logo,
                intent = ctx.packageManager.getLeanbackLaunchIntentForPackage(prov.second)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Log.i("Glass", "tmdb: ${out.size} novedades")
        return out
    }
}
