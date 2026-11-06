package mx.oli.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Sonidos propios del launcher (los "beeps" del sistema se apagan en esta app). */
class Sounds(ctx: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val palettes: Map<String, Triple<Int, Int, Int>> = mapOf(
        "seda" to Triple(R.raw.seda_mover, R.raw.seda_elegir, R.raw.seda_atras),
        "aire" to Triple(R.raw.aire_mover, R.raw.aire_elegir, R.raw.aire_atras),
        "cristal" to Triple(R.raw.cristal_mover, R.raw.cristal_elegir, R.raw.cristal_atras),
    ).mapValues { (_, r) ->
        Triple(pool.load(ctx, r.first, 1), pool.load(ctx, r.second, 1), pool.load(ctx, r.third, 1))
    }

    /** "seda", "aire", "cristal" u "off". */
    var palette = Config.SOUND_PALETTE

    /** En modo noche todo suena más bajo. */
    var quiet = false

    private fun play(pick: (Triple<Int, Int, Int>) -> Int) {
        if (palette == "off") return
        val ids = palettes[palette] ?: palettes.getValue(Config.SOUND_PALETTE)
        val v = Config.SOUND_VOLUME * (if (quiet) 0.4f else 1f)
        pool.play(pick(ids), v, v, 0, 0, 1f)
    }

    fun move() = play { it.first }
    fun select() = play { it.second }
    fun back() = play { it.third }
    fun release() = pool.release()
}

/** Preferencias que cambias desde el centro de control. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    var night: String
        get() = sp.getString("night", "auto") ?: "auto"
        set(v) = sp.edit().putString("night", v).apply()

    var sound: String
        get() = sp.getString("sound", Config.SOUND_PALETTE) ?: Config.SOUND_PALETTE
        set(v) = sp.edit().putString("sound", v).apply()
}
