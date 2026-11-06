package mx.oli.glass

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Medidas del lienzo de diseño (1920x1080).
private const val SIDE = 96f          // margen del dock
private const val PAD = 28f           // relleno interior del dock
private const val TILE_W = 249f
private const val TILE_H = 140f
private const val GAP = 36f
private const val DOCK_H = TILE_H + 2 * 26f
private const val DOCK_Y = 1080f - 70f - DOCK_H   // la primera fila de "todas las apps" asoma abajo
private const val ROW_STEP = TILE_H + 44f
private const val GRID_TOP = DOCK_Y + DOCK_H + 40f
private const val PER_ROW = 6

/** Estado de la pantalla de inicio (vive en la Activity, sobrevive recomposiciones). */
class HomeState(prefs: Prefs) {
    var now by mutableStateOf(Calendar.getInstance())
    var dock by mutableStateOf<List<AppEntry>>(emptyList())
    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
    var showControl by mutableStateOf(false)
    var inputs by mutableStateOf<List<InputEntry>>(emptyList())
    var weather by mutableStateOf<Weather?>(null)

    /** Fila enfocada: 0 = dock, 1.. = filas de todas las apps. */
    var row by mutableIntStateOf(0)
    var focusIndex by mutableIntStateOf(0)
    var gallery by mutableStateOf(false)
    var switching by mutableStateOf<InputEntry?>(null)
    var homeSignal by mutableIntStateOf(0)

    /** "auto", "on" u "off". */
    var nightMode by mutableStateOf(prefs.night)
    var soundPalette by mutableStateOf(prefs.sound)

    @Volatile var lastInput = SystemClock.uptimeMillis()
    @Volatile var resumed = false

    val overlayOpen get() = showControl || switching != null || row > 0

    val isNight: Boolean
        get() = when (nightMode) {
            "on" -> true
            "off" -> false
            else -> now.get(Calendar.HOUR_OF_DAY).let { it >= Config.NIGHT_START || it < Config.NIGHT_END }
        }

    /** ¿El cambio de foco vino de una tecla del control? (para no sonar al arrancar) */
    fun userDriven() = SystemClock.uptimeMillis() - lastInput < 400
}

private val ES = Locale("es", "MX")

// ---------- Raíz ----------

@Composable
fun GlassApp(state: HomeState, sounds: Sounds, prefs: Prefs, launch: (Intent) -> Unit) {
    val view = LocalView.current
    SideEffect { view.isSoundEffectsEnabled = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Diseñamos en un lienzo de 1920x1080: 1dp de diseño = 1 pixel de una pantalla 1080p.
        val real = LocalDensity.current
        val widthPx = with(real) { maxWidth.toPx() }
        val design = remember(widthPx) { Density(density = widthPx / 1920f, fontScale = 1f) }

        val progress: State<Float> = if (Config.DRIFT) {
            rememberInfiniteTransition(label = "drift").animateFloat(
                0f, 1f, infiniteRepeatable(tween(45_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
            )
        } else {
            remember { mutableFloatStateOf(0f) }
        }
        val backdrop = remember { Backdrop(progress) }

        CompositionLocalProvider(LocalDensity provides design, LocalBackdrop provides backdrop) {
            Home(state, sounds, prefs, launch)
        }
    }
}

@Composable
private fun Home(state: HomeState, sounds: Sounds, prefs: Prefs, launch: (Intent) -> Unit) {
    val ctx = LocalContext.current
    val b = LocalBackdrop.current

    LaunchedEffect(Unit) {
        while (true) {
            state.now = Calendar.getInstance()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }

    val art = ArtCatalog.pick(state.now)
    LaunchedEffect(art.slug) {
        val imgs = withContext(Dispatchers.IO) { ArtLoader.load(ctx, art) }
        b.art = art
        b.images = imgs
    }

    LaunchedEffect(Unit) {
        val dock = withContext(Dispatchers.IO) { AppRepo.loadDock(ctx) }
        state.dock = dock
        val inDock = dock.map { it.pkg }.toSet()
        state.allApps = withContext(Dispatchers.IO) { AppRepo.loadAll(ctx) }.filter { it.pkg !in inDock }
    }
    LaunchedEffect(state.homeSignal, state.resumed) {
        state.inputs = withContext(Dispatchers.IO) { InputRepo.load(ctx) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val w = withContext(Dispatchers.IO) { WeatherRepo.fetch() }
            if (w != null) state.weather = w
            delay(if (w != null) 30 * 60_000L else 2 * 60_000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val idle = SystemClock.uptimeMillis() - state.lastInput
            if (state.resumed && !state.gallery && !state.overlayOpen && idle > Config.GALLERY_IDLE_MS) {
                state.gallery = true
            }
        }
    }

    LaunchedEffect(state.switching) {
        val target = state.switching ?: return@LaunchedEffect
        delay(650)
        launch(InputRepo.intent(target.id))
        delay(2500)
        state.switching = null
    }

    SideEffect {
        sounds.quiet = state.isNight
        sounds.palette = state.soundPalette
    }

    val uiAlpha by animateFloatAsState(if (state.gallery) 0f else 1f, tween(700), label = "ui")
    // Desplazamiento vertical: el dock sube y aparece la cuadrícula de todas las apps.
    val scrollTarget = if (state.row == 0) 0f else -(DOCK_Y - 120f) - (state.row - 3).coerceAtLeast(0) * ROW_STEP
    val scroll by animateFloatAsState(scrollTarget, spring(dampingRatio = 0.9f, stiffness = 260f), label = "scroll")
    val gridDim by animateFloatAsState(if (state.row > 0) 1f else 0f, tween(320), label = "dim")

    Box(Modifier.fillMaxSize().onSizeChanged { b.screen = it.toSize() }) {
        val night = state.isNight
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            paintArt(b, blurred = false)
            val veil = 1f - 0.6f * (1f - uiAlpha)
            // Velo suave arriba a la izquierda (para el nombre) y abajo (para el dock).
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0x9905080E), Color(0x0005080E)),
                    center = Offset(0f, 0f),
                    radius = size.width * 0.55f,
                ),
                alpha = veil,
            )
            drawRect(
                Brush.verticalGradient(0.55f to Color(0x0005080E), 1f to Color(0xB305080E)),
                alpha = veil,
            )
            if (night) drawRect(Color(0x5C080604))
        }
        // Al bajar a la cuadrícula la obra se oscurece.
        if (gridDim > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = gridDim }.background(Color(0xB3050608)))

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
            Title(state, Modifier.align(Alignment.TopStart).padding(start = 120.dp, top = 96.dp).graphicsLayer { alpha = 1f - gridDim })
            ArtCredit(Modifier.align(Alignment.TopStart).offset(x = 0.dp, y = (DOCK_Y - 64f).dp).fillMaxWidth()
                .padding(end = 124.dp).graphicsLayer { alpha = 1f - gridDim })
            TopRight(state, sounds, launch, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 96.dp))
            Apps(state, sounds, launch, Modifier.graphicsLayer { translationY = scroll * density })
        }

        AnimatedVisibility(state.gallery, enter = fadeIn(tween(900)), exit = fadeOut(tween(400))) {
            GalleryOverlay(state)
        }
        AnimatedVisibility(
            state.showControl,
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            ControlCenter(state, sounds, prefs, launch)
        }
        state.switching?.let { InputOverlay(it) }
    }
}

// ---------- Piezas ----------

@Composable
fun Txt(
    text: String,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    spacing: TextUnit = 0.em,
    family: FontFamily = FontFamily.SansSerif,
    shadow: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFamily = family,
            letterSpacing = spacing,
            shadow = if (shadow) Shadow(Color(0x80000000), Offset(0f, 3f), 22f) else null,
        ),
    )
}

@Composable
private fun Seal(text: String, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(Color(0xFFB8322A)),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, (size.value * 0.55f).toInt(), FontWeight.Bold, Color(0xFFFFF4EA), family = FontFamily.Serif)
    }
}

private fun timeText(c: Calendar) = SimpleDateFormat("h:mm a", ES).format(c.time)
    .replace(". ", "").replace(".", "").uppercase(ES)
private fun clockText(c: Calendar) = SimpleDateFormat("h:mm", ES).format(c.time)

/** El nombre de la app enfocada, grande, directo sobre la obra. */
@Composable
private fun Title(state: HomeState, modifier: Modifier) {
    val name = state.dock.getOrNull(state.focusIndex)?.label ?: ""
    Txt(name, 112, FontWeight.ExtraBold, spacing = (-0.035).em, shadow = true, modifier = modifier)
}

/** Título y autor de la obra: texto pequeño y discreto, sin tarjeta. */
@Composable
private fun ArtCredit(modifier: Modifier) {
    val b = LocalBackdrop.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(b.art.title, 19, FontWeight.Medium, Color.White.copy(alpha = 0.7f), family = FontFamily.Serif, shadow = true)
        Txt("${b.art.artist} · ${b.art.year}", 15, color = Color.White.copy(alpha = 0.5f), shadow = true)
    }
}

/** Arriba a la derecha: píldora de hora y clima, y botones circulares. */
@Composable
private fun TopRight(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit, modifier: Modifier) {
    val onMove = { if (state.userDriven()) sounds.move() }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Glass(Modifier.height(64.dp), RoundedCornerShape(32.dp)) {
            Row(
                Modifier.fillMaxHeight().padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt(timeText(state.now), 26, FontWeight.SemiBold)
                state.weather?.let { w ->
                    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.25f)))
                    val ico = when {
                        w.isRain -> Ico.RAIN
                        w.isClear -> if (w.isDay) Ico.SUN else Ico.MOON
                        else -> Ico.CLOUD
                    }
                    Icon(ico, Color.White.copy(alpha = 0.9f), 26.dp)
                    Txt("${w.temp}°", 26, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
                }
            }
        }
        state.inputs.filter { it.active }.forEach { input ->
            RoundButton(Ico.HDMI, input.name, onMove) { sounds.select(); state.switching = input }
        }
        RoundButton(Ico.SEARCH, null, onMove) {
            sounds.select()
            launch(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        RoundButton(Ico.SLIDERS, null, onMove, selected = state.showControl) {
            sounds.select()
            state.showControl = true
        }
    }
}

@Composable
private fun RoundButton(icon: Ico, title: String?, onFocusMove: () -> Unit, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, spring(0.8f, 600f), label = "s")
    val lit = focused || selected
    val fg = if (lit) Color(0xFF0D0F14) else Color.White
    Glass(
        Modifier
            .height(64.dp)
            .then(if (title == null) Modifier.width(64.dp) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocusMove()
                focused = it.isFocused
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        CircleShape.takeIf { title == null } ?: RoundedCornerShape(32.dp),
    ) {
        if (lit) Box(Modifier.matchParentSize().background(Color.White))
        Row(
            Modifier.fillMaxHeight().then(if (title == null) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 22.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, fg, 26.dp)
            if (title != null) Txt(title, 21, FontWeight.SemiBold, fg)
        }
    }
}

/** Dock de vidrio oscuro + cuadrícula de todas las apps debajo. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Apps(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.dock.size, state.homeSignal) {
        delay(120)
        runCatching { first.requestFocus() }
        state.focusIndex = 0
        state.row = 0
    }
    Box(modifier.fillMaxSize()) {
        Glass(
            Modifier.offset(x = SIDE.dp, y = DOCK_Y.dp).width((1920f - 2 * SIDE).dp).height(DOCK_H.dp),
            RoundedCornerShape(40.dp),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = PAD.dp).focusRestorer().focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GAP.dp),
            ) {
                state.dock.forEachIndexed { i, app ->
                    Tile(app, if (i == 0) first else null, onFocused = {
                        if (state.userDriven()) sounds.move()
                        state.focusIndex = i
                        state.row = 0
                    }) { sounds.select(); launch(app.intent) }
                }
            }
        }
        state.allApps.chunked(PER_ROW).forEachIndexed { r, rowApps ->
            Row(
                Modifier.offset(x = (SIDE + PAD).dp, y = (GRID_TOP + r * ROW_STEP).dp).focusRestorer().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(GAP.dp),
            ) {
                rowApps.forEach { app ->
                    Tile(app, null, onFocused = {
                        if (state.userDriven()) sounds.move()
                        state.row = r + 1
                    }) { sounds.select(); launch(app.intent) }
                }
            }
        }
    }
}

@Composable
private fun Tile(app: AppEntry, requester: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    TileBase(app.banner, app.label, requester, onFocused, onClick)
}

@Composable
private fun TileBase(
    banner: ImageBitmap?,
    label: String,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val springy = spring<Float>(dampingRatio = 0.75f, stiffness = 520f)
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, springy, label = "scale")
    val shine by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "shine")
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .size(TILE_W.dp, TILE_H.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused()
                focused = it.isFocused
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = 0f }
            .clip(shape)
            .background(Color(0xFF1E2129)),
    ) {
        if (banner != null) {
            Image(banner, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // Brillo superior y borde de luz al enfocar (como el reflejo de los íconos de Apple TV).
        if (shine > 0f) {
            Box(
                Modifier.matchParentSize().graphicsLayer { alpha = shine }
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = 160f))
                    .border(2.dp, Color.White.copy(alpha = 0.55f), shape),
            )
        }
    }
}

// ---------- Capas ----------

@Composable
private fun GalleryOverlay(state: HomeState) {
    val b = LocalBackdrop.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.66f to Color.Transparent, 1f to Color(0x8005080E))))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 96.dp, end = 96.dp, bottom = 72.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Seal(b.art.moment.seal, 48.dp)
            Spacer(Modifier.width(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(b.art.title, 28, FontWeight.Bold, family = FontFamily.Serif, shadow = true)
                Txt("${b.art.artist} · ${b.art.year}", 19, color = Color.White.copy(alpha = 0.8f), shadow = true)
            }
            Spacer(Modifier.weight(1f))
            Txt(clockText(state.now), 64, FontWeight.Light, spacing = (-0.02).em, shadow = true)
        }
    }
}

/** Centro de control: popover de vidrio oscuro con mosaicos, anclado arriba a la derecha. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ControlCenter(state: HomeState, sounds: Sounds, prefs: Prefs, launch: (Intent) -> Unit) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val onMove = { if (state.userDriven()) sounds.move() }

    fun adjust(dir: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        sounds.move()
    }

    val nightLabel = mapOf("auto" to "Automático", "on" to "Siempre", "off" to "Apagado")
    val nightNext = mapOf("auto" to "on", "on" to "off", "off" to "auto")
    val soundLabel = mapOf("off" to "Sin sonido", "seda" to "Seda", "aire" to "Aire", "cristal" to "Cristal")
    val soundNext = mapOf("off" to "seda", "seda" to "aire", "aire" to "cristal", "cristal" to "off")

    Box(Modifier.fillMaxSize().background(Color(0x40000000))) {
        Glass(
            Modifier.align(Alignment.TopEnd).padding(top = 140.dp, end = 96.dp).width(560.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            RoundedCornerShape(44.dp),
            dim = 0.55f,
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CcTile(Ico.MOON, "Modo noche", nightLabel[state.nightMode] ?: "", state.nightMode != "off", onMove,
                        Modifier.weight(1f).focusRequester(first)) {
                        val n = nightNext[state.nightMode] ?: "auto"
                        state.nightMode = n
                        prefs.night = n
                        sounds.select()
                    }
                    CcTile(Ico.IMAGE, "Galería", "Solo la obra", false, onMove, Modifier.weight(1f)) {
                        state.showControl = false
                        state.gallery = true
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CcTile(Ico.SPEAKER, "Sonidos", soundLabel[state.soundPalette] ?: "", state.soundPalette != "off", onMove,
                        Modifier.weight(1f)) {
                        val n = soundNext[state.soundPalette] ?: "off"
                        state.soundPalette = n
                        prefs.sound = n
                        sounds.palette = n
                        sounds.select()
                    }
                    CcTile(Ico.BT, "Bluetooth", "Audífonos y controles", false, onMove, Modifier.weight(1f)) {
                        state.showControl = false
                        launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                // Volumen
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CcRound("−", onMove) { adjust(AudioManager.ADJUST_LOWER) }
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(vol / maxVol.toFloat()).clip(RoundedCornerShape(6.dp)).background(Color.White))
                    }
                    CcRound("+", onMove) { adjust(AudioManager.ADJUST_RAISE) }
                }
                // Entradas
                if (state.inputs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.inputs.forEach { input ->
                            CcPill(input.name, input.active, onMove, Modifier.weight(1f)) {
                                sounds.select()
                                state.showControl = false
                                state.switching = input
                            }
                        }
                    }
                }
                CcPill("Ajustes de la tele", false, onMove, Modifier.fillMaxWidth(), icon = Ico.GEAR) {
                    sounds.select()
                    state.showControl = false
                    launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

@Composable
private fun rememberFocus(onFocusMove: () -> Unit): Pair<Boolean, Modifier> {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(0.8f, 600f), label = "s")
    return focused to Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged {
            if (it.isFocused && !focused) onFocusMove()
            focused = it.isFocused
        }
}

/** Mosaico grande del centro de control: ícono en círculo, título y estado. */
@Composable
private fun CcTile(
    icon: Ico, title: String, sub: String, on: Boolean, onFocusMove: () -> Unit, modifier: Modifier, onClick: () -> Unit,
) {
    val (focused, fm) = rememberFocus(onFocusMove)
    val bg = if (focused) Color.White else Color.White.copy(alpha = 0.09f)
    val fg = if (focused) Color(0xFF0D0F14) else Color.White
    Row(
        modifier
            .height(104.dp)
            .then(fm)
            .clip(RoundedCornerShape(30.dp))
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(if (on) Color(0xFF4C8DF6) else fg.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, if (on) Color.White else fg, 28.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, 21, FontWeight.SemiBold, fg)
            Txt(sub, 17, color = fg.copy(alpha = 0.65f))
        }
    }
}

@Composable
private fun CcRound(text: String, onFocusMove: () -> Unit, onClick: () -> Unit) {
    val (focused, fm) = rememberFocus(onFocusMove)
    Box(
        Modifier.size(64.dp).then(fm).clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.09f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 30, FontWeight.Medium, if (focused) Color(0xFF0D0F14) else Color.White) }
}

@Composable
private fun CcPill(
    text: String, on: Boolean, onFocusMove: () -> Unit, modifier: Modifier, icon: Ico? = null, onClick: () -> Unit,
) {
    val (focused, fm) = rememberFocus(onFocusMove)
    val fg = if (focused) Color(0xFF0D0F14) else Color.White
    Row(
        modifier.height(60.dp).then(fm).clip(RoundedCornerShape(30.dp))
            .background(
                when {
                    focused -> Color.White
                    on -> Color.White.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.09f)
                }
            )
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, fg, 24.dp)
        Txt(text, 19, FontWeight.SemiBold, fg)
    }
}

@Composable
private fun InputOverlay(input: InputEntry) {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(-1f, 2.5f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Box(Modifier.fillMaxSize().background(Color(0x8C000000))) {
        Glass(Modifier.align(Alignment.Center).width(600.dp), RoundedCornerShape(56.dp), dim = 0.55f) {
            Column(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(
                    Modifier.size(140.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ico.HDMI, Color.White, 76.dp) }
                Txt(input.name, 52, FontWeight.ExtraBold, spacing = (-0.03).em)
                if (input.name != input.port) Txt(input.port, 22, color = Color.White.copy(alpha = 0.75f))
                Box(
                    Modifier.width(260.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier.width(104.dp).fillMaxHeight()
                            .graphicsLayer { translationX = x * 104.dp.toPx() }
                            .clip(RoundedCornerShape(3.dp)).background(Color.White),
                    )
                }
            }
        }
    }
}
