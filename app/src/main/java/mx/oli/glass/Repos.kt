package mx.oli.glass

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// ---------- Apps ----------

data class AppEntry(
    val pkg: String,
    val label: String,
    val kind: String,
    val banner: ImageBitmap?,
    val intent: Intent,
)

object AppRepo {

    private fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun leanbackActivities(pm: PackageManager): List<ResolveInfo> =
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
        )

    fun loadDock(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val acts = leanbackActivities(pm).associateBy { it.activityInfo.packageName }
        return Config.DOCK.mapNotNull { spec ->
            val intent = launchIntent(pm, spec.pkg) ?: return@mapNotNull null
            val ri = acts[spec.pkg]
            AppEntry(spec.pkg, spec.name, spec.kind, bannerFor(ctx, pm, ri, spec.pkg), intent)
        }
    }

    fun loadAll(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return leanbackActivities(pm)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                val intent = launchIntent(pm, pkg) ?: return@mapNotNull null
                AppEntry(pkg, ri.loadLabel(pm).toString(), "", bannerFor(ctx, pm, ri, pkg), intent)
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun bannerFor(ctx: Context, pm: PackageManager, ri: ResolveInfo?, pkg: String): ImageBitmap? = try {
        val banner: Drawable? = ri?.activityInfo?.loadBanner(pm)
            ?: runCatching { pm.getApplicationBanner(pkg) }.getOrNull()
        if (banner != null) {
            banner.toBitmap(320, 180, Bitmap.Config.ARGB_8888).asImageBitmap()
        } else {
            val icon = ri?.activityInfo?.loadIcon(pm) ?: pm.getApplicationIcon(pkg)
            iconOnCard(icon).asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }

    private fun iconOnCard(icon: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1E2633.toInt())
        icon.setBounds(100, 30, 220, 150)
        icon.draw(c)
        return bmp
    }
}

// ---------- Entradas HDMI ----------

data class InputEntry(val id: String, val name: String, val port: String)

object InputRepo {
    fun load(ctx: Context): List<InputEntry> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        val list = try { tim.tvInputList } catch (e: Exception) { return emptyList() }
        val ports = list.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
        return ports.mapIndexed { i, info ->
            val port = info.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: "HDMI ${i + 1}"
            val custom = info.loadCustomLabel(ctx)?.toString()?.takeIf { it.isNotBlank() }
            // Un aparato con HDMI-CEC (T-Link) aparece como entrada "hija" con su propio nombre.
            val cec = list.firstOrNull { it.parentId == info.id }?.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() }
            InputEntry(info.id, custom ?: cec ?: port, port)
        }.sortedBy { it.port }
    }

    fun intent(id: String): Intent =
        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ---------- Clima ----------

data class Weather(val temp: Int, val max: Int, val min: Int, val code: Int, val isDay: Boolean) {
    val description: String
        get() = when (code) {
            0 -> "Despejado"
            1, 2 -> "Medio nublado"
            3 -> "Nublado"
            45, 48 -> "Niebla"
            in 51..57 -> "Llovizna"
            in 61..67 -> "Lluvia"
            in 71..77 -> "Nieve"
            in 80..82 -> "Chubascos"
            in 95..99 -> "Tormenta"
            else -> ""
        }
    val isClear get() = code <= 2
    val isRain get() = code in 51..67 || code in 80..82 || code >= 95
}

object WeatherRepo {
    fun fetch(): Weather? = try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${Config.LAT}&longitude=${Config.LON}" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=${Config.TIMEZONE.replace("/", "%2F")}&forecast_days=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        val json = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val root = JSONObject(json)
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        Weather(
            temp = cur.getDouble("temperature_2m").roundToInt(),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
        )
    } catch (e: Exception) {
        null
    }
}
