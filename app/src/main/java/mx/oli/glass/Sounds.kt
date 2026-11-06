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

    private val ids: Triple<Int, Int, Int> = when (Config.SOUND_PALETTE) {
        "madera" -> Triple(R.raw.madera_mover, R.raw.madera_elegir, R.raw.madera_atras)
        "minimo" -> Triple(R.raw.minimo_mover, R.raw.minimo_elegir, R.raw.minimo_atras)
        else -> Triple(R.raw.vidrio_mover, R.raw.vidrio_elegir, R.raw.vidrio_atras)
    }
    private val move = pool.load(ctx, ids.first, 1)
    private val select = pool.load(ctx, ids.second, 1)
    private val back = pool.load(ctx, ids.third, 1)

    /** En modo noche todo suena más bajo. */
    var quiet = false

    private fun play(id: Int) {
        val v = Config.SOUND_VOLUME * (if (quiet) 0.4f else 1f)
        pool.play(id, v, v, 0, 0, 1f)
    }

    fun move() = play(move)
    fun select() = play(select)
    fun back() = play(back)
    fun release() = pool.release()
}
