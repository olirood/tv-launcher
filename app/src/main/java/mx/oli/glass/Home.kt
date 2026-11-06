package mx.oli.glass

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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

/** Estado de la pantalla de inicio (vive en la Activity, sobrevive recomposiciones). */
class HomeState(prefs: Prefs) {
    var now by mutableStateOf(Calendar.getInstance())
    var dock by mutableStateOf<List<AppEntry>>(emptyList())
    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
    var showAll by mutableStateOf(false)
    var showControl by mutableStateOf(false)
    var inputs by mutableStateOf<List<InputEntry>>(emptyList())
    var weather by mutableStateOf<Weather?>(null)
    var focusIndex by mutableIntStateOf(0)
    var gallery by mutableStateOf(false)
    var switching by mutableStateOf<InputEntry?>(null)
    var homeSignal by mutableIntStateOf(0)

    /** "auto", "on" u "off". */
    var nightMode by mutableStateOf(prefs.night)
    var soundPalette by mutableStateOf(prefs.sound)

    @Volatile var lastInput = SystemClock.uptimeMillis()
    @Volatile var resumed = false

    val overlayOpen get() = showAll || showControl || switching != null

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
        state.dock = withContext(Dispatchers.IO) { AppRepo.loadDock(ctx) }
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

    LaunchedEffect(state.showAll) {
        if (state.showAll) {
            if (state.allApps.isEmpty()) state.allApps = withContext(Dispatchers.IO) { AppRepo.loadAll(ctx) }
        } else {
            state.allApps = emptyList()
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

    Box(Modifier.fillMaxSize().onSizeChanged { b.screen = it.toSize() }) {
        // Obra + velos + modo noche en una sola capa guardada: se pinta una vez, no en cada cuadro.
        val night = state.isNight
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            paintArt(b, blurred = false)
            val veil = 1f - 0.6f * (1f - uiAlpha)
            drawRect(
                Brush.horizontalGradient(0f to Color(0xA605080E), 0.42f to Color(0x4005080E), 0.7f to Color(0x0005080E)),
                alpha = veil,
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0x8005080E), 0.24f to Color(0x0005080E), 0.6f to Color(0x0005080E), 1f to Color(0x9905080E),
                ),
                alpha = veil,
            )
            if (night) drawRect(Color(0x5C080604))
        }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
            TopBar(state, sounds, launch, Modifier.align(Alignment.TopStart))
            Hero(state, Modifier.align(Alignment.TopStart).padding(start = 120.dp, top = 270.dp))
            ArtCredit(Modifier.align(Alignment.BottomEnd).padding(end = 128.dp, bottom = 286.dp))
            Dock(state, sounds, launch, Modifier.align(Alignment.BottomStart))
        }

        AnimatedVisibility(state.gallery, enter = fadeIn(tween(900)), exit = fadeOut(tween(400))) {
            GalleryOverlay(state)
        }
        AnimatedVisibility(state.showAll, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
            AllAppsOverlay(state, sounds, launch)
        }
        AnimatedVisibility(
            state.showControl,
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(260)) { it / 6 },
            exit = fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 6 },
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
            shadow = if (shadow) Shadow(Color(0x73000000), Offset(0f, 3f), 18f) else null,
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

private fun timeText(c: Calendar) = SimpleDateFormat("h:mm", ES).format(c.time)
private fun dateText(c: Calendar) = SimpleDateFormat("EEEE d 'de' MMMM", ES).format(c.time)
    .replaceFirstChar { it.titlecase(ES) }

@Composable
private fun TopBar(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 120.dp, end = 120.dp, top = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(timeText(state.now), 76, FontWeight.Bold, spacing = (-0.025).em, shadow = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(dateText(state.now), 24, FontWeight.Medium, Color.White.copy(alpha = 0.85f), shadow = true)
                state.weather?.let { w ->
                    Txt("·", 24, color = Color.White.copy(alpha = 0.5f))
                    val ico = when {
                        w.isRain -> Ico.RAIN
                        w.isClear -> if (w.isDay) Ico.SUN else Ico.MOON
                        else -> Ico.CLOUD
                    }
                    Icon(ico, Color.White.copy(alpha = 0.85f), 26.dp)
                    Txt("${w.temp}° ${Config.CITY}", 24, FontWeight.Medium, Color.White.copy(alpha = 0.85f), shadow = true)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val onMove = { if (state.userDriven()) sounds.move() }
            PillButton(Ico.SEARCH, onFocusMove = onMove) {
                sounds.select()
                launch(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Solo las entradas con algo conectado; todas están en el centro de control.
            state.inputs.filter { it.active }.forEach { input ->
                PillButton(Ico.HDMI, input.name, onFocusMove = onMove) {
                    sounds.select()
                    state.switching = input
                }
            }
            PillButton(Ico.SLIDERS, onFocusMove = onMove) {
                sounds.select()
                state.showControl = true
            }
        }
    }
}

@Composable
private fun PillButton(
    icon: Ico,
    title: String? = null,
    onFocusMove: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(0.8f, 600f), label = "s")
    val fill by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "f")
    val fg = if (focused) Color(0xFF0D0F14) else Color.White
    val shape = RoundedCornerShape(36.dp)
    Glass(
        Modifier
            .height(72.dp)
            .then(if (title == null) Modifier.width(72.dp) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged {
                if (it.isFocused && !focused) onFocusMove()
                focused = it.isFocused
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
        shape,
    ) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = fill }.background(Color.White))
        Row(
            Modifier.fillMaxHeight().padding(horizontal = if (title == null) 0.dp else 24.dp)
                .then(if (title == null) Modifier.fillMaxWidth() else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        ) {
            Icon(icon, fg, 28.dp)
            if (title != null) Txt(title, 21, FontWeight.SemiBold, fg)
        }
    }
}

/** El nombre de la app enfocada, sobre una placa de vidrio oscuro con su banner. */
@Composable
private fun Hero(state: HomeState, modifier: Modifier) {
    val app = state.dock.getOrNull(state.focusIndex)
    val name = app?.label ?: "Más apps"
    Glass(modifier.animateContentSize(spring(0.9f, 500f)), RoundedCornerShape(40.dp), dim = 0.34f) {
        Row(
            Modifier.padding(start = 24.dp, end = 52.dp, top = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            Box(
                Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                val banner = app?.banner
                if (banner != null) {
                    Image(banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Ico.GRID, Color.White, 56.dp)
                }
            }
            Txt(name, 100, FontWeight.ExtraBold, spacing = (-0.03).em)
        }
    }
}

/** Título y autor de la obra: texto pequeño y discreto, sin tarjeta. */
@Composable
private fun ArtCredit(modifier: Modifier) {
    val b = LocalBackdrop.current
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(b.art.title, 20, FontWeight.Medium, Color.White.copy(alpha = 0.72f), family = FontFamily.Serif, shadow = true)
        Txt("${b.art.artist} · ${b.art.year}", 16, color = Color.White.copy(alpha = 0.55f), shadow = true)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Dock(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.dock.size, state.homeSignal) {
        delay(120)
        runCatching { first.requestFocus() }
        state.focusIndex = 0
    }
    Glass(
        modifier.padding(start = 120.dp, end = 120.dp, bottom = 60.dp).fillMaxWidth(),
        RoundedCornerShape(44.dp),
    ) {
        Row(
            Modifier.padding(start = 32.dp, end = 32.dp, top = 30.dp, bottom = 16.dp).focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            state.dock.forEachIndexed { i, app ->
                Tile(
                    label = app.label,
                    banner = app.banner,
                    icon = null,
                    requester = if (i == 0) first else null,
                    onFocused = {
                        if (state.userDriven()) sounds.move()
                        state.focusIndex = i
                    },
                    onClick = { sounds.select(); launch(app.intent) },
                )
            }
            Tile(
                label = "Más apps",
                banner = null,
                icon = Ico.GRID,
                requester = if (state.dock.isEmpty()) first else null,
                onFocused = {
                    if (state.userDriven()) sounds.move()
                    state.focusIndex = state.dock.size
                },
                onClick = { sounds.select(); state.showAll = true },
            )
        }
    }
}

@Composable
private fun Tile(
    label: String,
    banner: ImageBitmap?,
    icon: Ico?,
    requester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    width: Dp = 203.dp,
    height: Dp = 120.dp,
    showLabel: Boolean = false,
) {
    val b = LocalBackdrop.current
    var focused by remember { mutableStateOf(false) }
    val springy = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, springy, label = "scale")
    val lift by animateFloatAsState(if (focused) -10f else 0f, springy, label = "lift")
    val shape = RoundedCornerShape(26.dp)

    Column(
        Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Glass(
            Modifier
                .size(width, height)
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged {
                    if (it.isFocused && !focused) onFocused()
                    focused = it.isFocused
                }
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift * density
                },
            shape,
            backdrop = banner == null,
        ) {
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(20.dp)),
                )
            } else if (icon != null) {
                Icon(icon, Color.White, 46.dp, Modifier.align(Alignment.Center))
            }
            if (focused) Box(Modifier.matchParentSize().border(3.dp, Color.White, shape))
        }
        // Marca de foco: una barra corta del color de la obra bajo el mosaico.
        Box(
            Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(if (focused) b.accent else Color.Transparent),
        )
        if (showLabel) Txt(label, 20, FontWeight.Medium, Color.White.copy(alpha = if (focused) 1f else 0.75f))
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
            Txt(timeText(state.now), 64, FontWeight.Light, spacing = (-0.02).em, shadow = true)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AllAppsOverlay(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(state.allApps.size) {
        if (state.allApps.isNotEmpty()) {
            delay(80)
            runCatching { first.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x73000000))) {
        Glass(Modifier.fillMaxSize().padding(80.dp), RoundedCornerShape(48.dp), dim = 0.3f) {
            Column(Modifier.padding(start = 56.dp, end = 56.dp, top = 44.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("Apps", 44, FontWeight.Bold, spacing = (-0.02).em)
                if (state.allApps.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 22.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
                        modifier = Modifier.fillMaxSize()
                            .focusProperties { exit = { FocusRequester.Cancel } }
                            .focusGroup(),
                    ) {
                        itemsIndexed(state.allApps, key = { _, a -> a.pkg }) { i, app ->
                            Tile(
                                label = app.label,
                                banner = app.banner,
                                icon = null,
                                requester = if (i == 0) first else null,
                                onFocused = { if (state.userDriven()) sounds.move() },
                                onClick = { sounds.select(); state.showAll = false; launch(app.intent) },
                                width = 270.dp,
                                height = 152.dp,
                                showLabel = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

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

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Glass(
            Modifier.align(Alignment.CenterEnd).padding(top = 40.dp, end = 40.dp, bottom = 40.dp).width(620.dp).fillMaxHeight()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            RoundedCornerShape(48.dp),
            dim = 0.32f,
        ) {
            Column(
                Modifier.padding(start = 44.dp, end = 44.dp, top = 44.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                Section("Entradas") {
                    if (state.inputs.isEmpty()) {
                        Txt("No se encontraron entradas", 20, color = Color.White.copy(alpha = 0.7f))
                    }
                    state.inputs.chunked(2).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEachIndexed { c, input ->
                                Chip(
                                    input.name, selected = input.active, onFocusMove = onMove,
                                    modifier = Modifier.weight(1f)
                                        .then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                                ) {
                                    sounds.select()
                                    state.showControl = false
                                    state.switching = input
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Section("Volumen") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Chip("−", false, onFocusMove = onMove, modifier = Modifier.width(76.dp)
                            .then(if (state.inputs.isEmpty()) Modifier.focusRequester(first) else Modifier)) {
                            adjust(AudioManager.ADJUST_LOWER)
                        }
                        Box(
                            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.16f)),
                        ) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(vol / maxVol.toFloat())
                                    .clip(RoundedCornerShape(7.dp)).background(Color.White),
                            )
                        }
                        Chip("+", false, onFocusMove = onMove, modifier = Modifier.width(76.dp)) {
                            adjust(AudioManager.ADJUST_RAISE)
                        }
                    }
                }

                Section("Modo noche") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("auto" to "Automático", "on" to "Siempre", "off" to "Nunca").forEach { (k, l) ->
                            Chip(l, state.nightMode == k, onFocusMove = onMove, modifier = Modifier.weight(1f)) {
                                sounds.select()
                                state.nightMode = k
                                prefs.night = k
                            }
                        }
                    }
                }

                Section("Sonidos") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("vidrio" to "Vidrio", "madera" to "Madera", "minimo" to "Suave", "off" to "Sin").forEach { (k, l) ->
                            Chip(l, state.soundPalette == k, onFocusMove = onMove, modifier = Modifier.weight(1f)) {
                                state.soundPalette = k
                                prefs.sound = k
                                sounds.palette = k
                                sounds.select()
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("Galería", false, onFocusMove = onMove, modifier = Modifier.weight(1f)) {
                        state.showControl = false
                        state.gallery = true
                    }
                    Chip("Bluetooth", false, onFocusMove = onMove, modifier = Modifier.weight(1f)) {
                        sounds.select()
                        state.showControl = false
                        launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Chip("Ajustes", false, onFocusMove = onMove, modifier = Modifier.weight(1f)) {
                        sounds.select()
                        state.showControl = false
                        launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Txt(title, 20, FontWeight.SemiBold, Color.White.copy(alpha = 0.7f))
        content()
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    onFocusMove: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(0.8f, 600f), label = "s")
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.3f)
        else -> Color.White.copy(alpha = 0.1f)
    }
    Box(
        modifier
            .height(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(32.dp))
            .background(bg)
            .onFocusChanged {
                if (it.isFocused && !focused) onFocusMove()
                focused = it.isFocused
            }
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 21, FontWeight.SemiBold, if (focused) Color(0xFF0D0F14) else Color.White)
    }
}

@Composable
private fun InputOverlay(input: InputEntry) {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(-1f, 2.5f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Box(Modifier.fillMaxSize().background(Color(0x8C000000))) {
        Glass(Modifier.align(Alignment.Center).width(600.dp), RoundedCornerShape(56.dp), dim = 0.3f) {
            Column(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(
                    Modifier.size(140.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
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
