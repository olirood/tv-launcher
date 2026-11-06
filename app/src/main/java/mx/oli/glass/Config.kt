package mx.oli.glass

/** Todo lo que se ajusta a mano vive aquí. */
object Config {

    data class DockSpec(val pkg: String, val name: String, val kind: String)

    /** Apps del dock, en orden. */
    val DOCK = listOf(
        DockSpec("com.apple.atve.androidtv.appletv", "Apple TV", "Series y películas"),
        DockSpec("com.google.android.youtube.tv", "YouTube", "Videos"),
        DockSpec("com.amazon.amazonvideo.livingroom", "Prime Video", "Series y películas"),
        DockSpec("com.wbd.stream", "Max", "Series y películas"),
        DockSpec("tv.twitch.android.app", "Twitch", "En vivo"),
        DockSpec("com.netflix.ninja", "Netflix", "Series y películas"),
    )

    /** Clima (Open-Meteo, sin llave). */
    const val CITY = "Culiacán"
    const val LAT = 24.8091
    const val LON = -107.3940
    const val TIMEZONE = "America/Mazatlan"

    /** Paleta de sonidos inicial: "seda", "aire", "cristal" u "off". Se cambia en el centro de control. */
    const val SOUND_PALETTE = "seda"
    const val SOUND_VOLUME = 0.6f

    /** Minutos sin tocar el control para entrar al modo galería. */
    const val GALLERY_IDLE_MS = 3 * 60 * 1000L

    /** Modo noche: de NIGHT_START a NIGHT_END (horas). */
    const val NIGHT_START = 20
    const val NIGHT_END = 5

    /** Movimiento lento del fondo. Apagado: imagen fija, nítida y mucho más fluida en esta tele. */
    const val DRIFT = false
}
