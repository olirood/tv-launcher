package mx.oli.glass

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
class HomeState {
    var now by mutableStateOf(Calendar.getInstance())
    var dock by mutableStateOf<List<AppEntry>>(emptyList())
    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
    var showAll by mutableStateOf(false)
    var inputs by mutableStateOf<List<InputEntry>>(emptyList())
    var weather by mutableStateOf<Weather?>(null)
    var focusIndex by mutableIntStateOf(0)
    var gallery by mutableStateOf(false)
    var switching by mutableStateOf<InputEntry?>(null)
    var homeSignal by mutableIntStateOf(0)

    @Volatile var lastInput = SystemClock.uptimeMillis()
    @Volatile var resumed = false

    val isNight: Boolean
        get() = now.get(Calendar.HOUR_OF_DAY).let { it >= Config.NIGHT_START || it < Config.NIGHT_END }

    /** ¿El cambio de foco vino de una tecla del control? (para no sonar al arrancar) */
    fun userDriven() = SystemClock.uptimeMillis() - lastInput < 400
}

private val ES = Locale("es", "MX")

// ---------- Raíz ----------

@Composable
fun GlassApp(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit) {
    val view = LocalView.current
    SideEffect { view.isSoundEffectsEnabled = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Diseñamos en un lienzo de 1920x1080: 1dp de diseño = 1 pixel de una pantalla 1080p.
        val real = LocalDensity.current
        val widthPx = with(real) { maxWidth.toPx() }
        val design = remember(widthPx) { Density(density = widthPx / 1920f, fontScale = 1f) }

        val drift = rememberInfiniteTransition(label = "drift")
        val progress = drift.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(45_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "progress",
        )
        val backdrop = remember { Backdrop(progress) }

        CompositionLocalProvider(LocalDensity provides design, LocalBackdrop provides backdrop) {
            Home(state, sounds, launch)
        }
    }
}

@Composable
private fun Home(state: HomeState, sounds: Sounds, launch: (Intent) -> Unit) {
    val ctx = LocalContext.current
    val b = LocalBackdrop.current

    // Reloj: se actualiza al cambiar el minuto.
    LaunchedEffect(Unit) {
        while (true) {
            state.now = Calendar.getInstance()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }

    // Obra del momento: cambia sola al pasar de amanecer a día, etc.
    val art = ArtCatalog.pick(state.now)
    LaunchedEffect(art.slug) {
        val imgs = withContext(Dispatchers.IO) { ArtLoader.load(ctx, art) }
        b.art = art
        b.images = imgs
    }

    // Apps y entradas.
    LaunchedEffect(Unit) {
        state.dock = withContext(Dispatchers.IO) { AppRepo.loadDock(ctx) }
        state.inputs = withContext(Dispatchers.IO) { InputRepo.load(ctx) }
    }

    // Clima cada 30 min (reintenta en 2 si no hay red).
    LaunchedEffect(Unit) {
        while (true) {
            val w = withContext(Dispatchers.IO) { WeatherRepo.fetch() }
            if (w != null) state.weather = w
            delay(if (w != null) 30 * 60_000L else 2 * 60_000L)
        }
    }

    // Modo galería tras unos minutos sin tocar el control.
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val idle = SystemClock.uptimeMillis() - state.lastInput
            if (state.resumed && !state.gallery && !state.showAll && state.switching == null && idle > Config.GALLERY_IDLE_MS) {
                state.gallery = true
            }
        }
    }

    // Todas las apps: se cargan al abrir y se liberan al cerrar (la tele tiene poca RAM).
    LaunchedEffect(state.showAll) {
        if (state.showAll) {
            if (state.allApps.isEmpty()) state.allApps = withContext(Dispatchers.IO) { AppRepo.loadAll(ctx) }
        } else {
            state.allApps = emptyList()
        }
    }

    // Cambio de entrada: tarjeta de vidrio mientras la tele sincroniza el HDMI.
    LaunchedEffect(state.switching) {
        val target = state.switching ?: return@LaunchedEffect
        delay(650)
        launch(InputRepo.intent(target.id))
        delay(2500)
        state.switching = null
    }

    SideEffect { sounds.quiet = state.isNight }

    val uiAlpha by animateFloatAsState(if (state.gallery) 0f else 1f, tween(700), label = "ui")

    Box(Modifier.fillMaxSize().onSizeChanged { b.screen = it.toSize() }) {
        // Obra + velos para que el texto se lea.
        Canvas(Modifier.fillMaxSize()) {
            paintArt(b, blurred = false)
            val veil = 1f - 0.6f * (1f - uiAlpha)
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(0xB805080E), 0.45f to Color(0x5905080E), 0.75f to Color(0x0005080E),
                ),
                alpha = veil,
            )
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0x8C05080E), 0.26f to Color(0x0005080E), 0.55f to Color(0x0005080E), 1f to Color(0x9905080E),
                ),
                alpha = veil,
            )
        }
        if (state.isNight) Box(Modifier.fillMaxSize().background(Color(0x5C080604)))

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
            TopBar(state, sounds, launch, Modifier.align(Alignment.TopStart))
            Hero(state, Modifier.align(Alignment.TopStart))
            ArtLabel(Modifier.align(Alignment.BottomEnd).padding(end = 120.dp, bottom = 380.dp))
            Dock(state, sounds, launch, Modifier.align(Alignment.BottomStart))
        }

        AnimatedVisibility(state.gallery, enter = fadeIn(tween(900)), exit = fadeOut(tween(400))) {
            GalleryOverlay(state)
        }
        AnimatedVisibility(state.showAll, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            AllAppsOverlay(state, sounds, launch)
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
            shadow = if (shadow) Shadow(Color(0x59000000), Offset(0f, 6f), 40f) else null,
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
        modifier.fillMaxWidth().padding(start = 120.dp, end = 120.dp, top = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(timeText(state.now), 54, FontWeight.Bold, spacing = (-0.02).em)
            Txt(dateText(state.now), 24, FontWeight.Medium, Color.White.copy(alpha = 0.82f))
        }
        Spacer(Modifier.width(36.dp))
        WeatherPill(state.weather)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val onMove = { if (state.userDriven()) sounds.move() }
            PillButton(Ico.SEARCH, onFocusMove = onMove) {
                sounds.select()
                launch(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            state.inputs.forEach { input ->
                PillButton(Ico.HDMI, input.name, if (input.name != input.port) input.port else null, onFocusMove = onMove) {
                    sounds.select()
                    state.switching = input
                }
            }
            PillButton(Ico.SLIDERS, onFocusMove = onMove) {
                sounds.select()
                launch(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
private fun WeatherPill(w: Weather?) {
    Glass(Modifier.height(84.dp), RoundedCornerShape(42.dp)) {
        Row(
            Modifier.fillMaxHeight().padding(start = 22.dp, end = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val ico = when {
                w == null -> Ico.CLOUD
                w.isRain -> Ico.RAIN
                w.isClear -> if (w.isDay) Ico.SUN else Ico.MOON
                else -> Ico.CLOUD
            }
            Icon(ico, Color.White, 40.dp)
            Txt(w?.let { "${it.temp}°" } ?: "--°", 40, FontWeight.SemiBold, spacing = (-0.02).em)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(Config.CITY, 20, FontWeight.SemiBold)
                Txt(
                    w?.let { "${it.description} · Máx ${it.max}° · Mín ${it.min}°" } ?: "Buscando clima…",
                    18, color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun PillButton(
    icon: Ico,
    title: String? = null,
    sub: String? = null,
    onFocusMove: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(0.8f, 600f), label = "s")
    val fill by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "f")
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
            if (title != null) {
                Column {
                    Txt(title, 21, FontWeight.SemiBold, fg)
                    if (sub != null) Txt(sub, 16, color = fg.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun Hero(state: HomeState, modifier: Modifier) {
    val b = LocalBackdrop.current
    val i = state.focusIndex
    val (kind, name) = state.dock.getOrNull(i)?.let { it.kind to it.label } ?: ("Biblioteca" to "Más apps")
    Column(
        modifier.padding(start = 120.dp, top = 290.dp).width(1100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Txt(kind.uppercase(ES), 24, FontWeight.SemiBold, b.accent, spacing = 0.12.em)
        Txt(name, 128, FontWeight.ExtraBold, spacing = (-0.035).em, shadow = true)
        Txt("Presiona OK para abrir", 28, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun ArtLabel(modifier: Modifier) {
    val b = LocalBackdrop.current
    Glass(modifier, RoundedCornerShape(26.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Seal(b.art.moment.seal, 48.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Txt(b.art.title, 24, FontWeight.Bold, family = FontFamily.Serif)
                Txt("${b.art.artist} · ${b.art.year}", 18, color = Color.White.copy(alpha = 0.82f))
            }
        }
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
        Column(
            Modifier.padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Txt("Tus apps", 22, FontWeight.SemiBold, Color.White.copy(alpha = 0.82f), Modifier.padding(start = 6.dp))
            Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
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
) {
    val b = LocalBackdrop.current
    var focused by remember { mutableStateOf(false) }
    val springy = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, springy, label = "scale")
    val lift by animateFloatAsState(if (focused) -12f else 0f, springy, label = "lift")
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "label")
    val shape = RoundedCornerShape(26.dp)

    Column(
        Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                }
                .shadow(if (focused) 28.dp else 0.dp, shape, clip = false),
            shape,
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
            if (focused) Box(Modifier.matchParentSize().border(3.dp, b.accent, shape))
        }
        Txt(
            label, 22, FontWeight.SemiBold,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha; translationY = (1f - labelAlpha) * -6f * density },
        )
    }
}

// ---------- Capas ----------

@Composable
private fun GalleryOverlay(state: HomeState) {
    val b = LocalBackdrop.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color(0x9905080E))))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 96.dp, end = 96.dp, bottom = 80.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Seal(b.art.moment.seal, 56.dp)
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(b.art.title, 34, FontWeight.Bold, family = FontFamily.Serif, shadow = true)
                Txt("${b.art.artist} · ${b.art.year} · ${b.art.series}", 21, color = Color.White.copy(alpha = 0.85f))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Txt(timeText(state.now), 64, FontWeight.Light, spacing = (-0.02).em)
                Txt("Cualquier botón para volver", 19, color = Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

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
        Glass(Modifier.fillMaxSize().padding(80.dp), RoundedCornerShape(48.dp)) {
            Column(Modifier.padding(start = 56.dp, end = 56.dp, top = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Txt("Todas tus apps", 48, FontWeight.Bold, spacing = (-0.02).em)
                if (state.allApps.isEmpty()) {
                    Txt("Cargando…", 24, color = Color.White.copy(alpha = 0.8f))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp, start = 16.dp, end = 16.dp),
                        modifier = Modifier.fillMaxSize(),
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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputOverlay(input: InputEntry) {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(-1f, 2.5f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Box(Modifier.fillMaxSize().background(Color(0x8C000000))) {
        Glass(Modifier.align(Alignment.Center).width(640.dp), RoundedCornerShape(56.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Box(
                    Modifier.size(148.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ico.HDMI, Color.White, 80.dp) }
                Txt(input.name, 52, FontWeight.ExtraBold, spacing = (-0.03).em)
                Txt("${input.port} · Cambiando de entrada", 24, color = Color.White.copy(alpha = 0.8f))
                Box(
                    Modifier.width(280.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier.width(112.dp).fillMaxHeight()
                            .graphicsLayer { translationX = x * 112.dp.toPx() }
                            .clip(RoundedCornerShape(3.dp)).background(Color.White),
                    )
                }
                Txt("Atrás para cancelar", 19, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
