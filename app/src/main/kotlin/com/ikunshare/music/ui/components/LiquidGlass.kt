package com.ikunshare.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.ikunshare.music.R
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException

// Screens opt in by providing a glass scene; other screens keep their own styling.
internal val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Read lazily from draw-phase lambdas so that starting or stopping a scroll only
 * invalidates the glass layers instead of recomposing every card in the list.
 */
internal val LocalLiquidGlassInMotion = staticCompositionLocalOf<() -> Boolean> { { false } }

/** Settles a little after the last scroll delta so a fling's tail does not flicker the lens. */
private const val GLASS_MOTION_SETTLE_MS = 180L

/** Tracks nested scrolling below a glass scene; moving cards skip their refraction pass. */
@Stable
private class GlassMotionTracker(private val scope: CoroutineScope) : NestedScrollConnection {
    var active by mutableStateOf(false)
        private set
    private var lastEventMs = 0L
    private var settleJob: Job? = null

    private fun mark() {
        lastEventMs = SystemClock.uptimeMillis()
        if (settleJob?.isActive == true) return
        active = true
        settleJob = scope.launch {
            while (true) {
                val idle = SystemClock.uptimeMillis() - lastEventMs
                if (idle >= GLASS_MOTION_SETTLE_MS) break
                delay(GLASS_MOTION_SETTLE_MS - idle)
            }
            active = false
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available != Offset.Zero) mark()
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        mark()
        return Velocity.Zero
    }
}

/** Kept for lists that want to be explicit; the scene already tracks nested scrolling. */
@Composable
internal fun ScrollGlassScope(state: LazyListState, content: @Composable () -> Unit) {
    val parent = LocalLiquidGlassInMotion.current
    val inMotion = remember(state, parent) { { parent() || state.isScrollInProgress } }
    CompositionLocalProvider(LocalLiquidGlassInMotion provides inMotion, content = content)
}

private const val AMBIENT_SNAPSHOT_DELAY_MS = 420L
private const val AMBIENT_SNAPSHOT_SCALE = 3

private data class AmbientKey(
    val cover: String?, val surface: Color, val primary: Color, val accent: Color, val size: IntSize
)

/** A few small ambient snapshots shared by every scene in the process. */
private object AmbientSnapshots {
    private const val MAX_ENTRIES = 8
    private val entries = object : LinkedHashMap<AmbientKey, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AmbientKey, ImageBitmap>?) =
            size > MAX_ENTRIES
    }

    operator fun get(key: AmbientKey): ImageBitmap? = entries[key]
    operator fun set(key: AmbientKey, image: ImageBitmap) { entries[key] = image }
}

@Composable
fun LiquidGlassScene(cover: String? = null, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val ambientLayer = rememberGraphicsLayer()
    val ambientBlur = remember(density) {
        val radius = with(density) { 16.dp.toPx() }
        BlurEffect(radiusX = radius, radiusY = radius)
    }
    // Filter the ambient layer once. Moving cards sample this shared layer;
    // they must not each apply another blur to a copy of the whole background.
    ambientLayer.renderEffect = ambientBlur
    val ambientBackdrop = rememberLayerBackdrop(graphicsLayer = ambientLayer)
    val cachedBackdrop = rememberLayerBackdrop()
    val colors = MaterialTheme.colorScheme
    val ambientSurface = colors.surface
    val ambientPrimary = colors.primary
    val ambientAccent = colors.tertiary
    val dark = ambientSurface.luminance() < 0.5f
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    var coverReady by remember(cover) { mutableStateOf(cover.isNullOrBlank()) }
    val cacheKey = remember(cover, ambientSurface, ambientPrimary, ambientAccent, sceneSize) {
        AmbientKey(cover, ambientSurface, ambientPrimary, ambientAccent, sceneSize)
    }
    // Scenes are recreated every time a page or tab returns; reuse the last snapshot
    // so re-entering never pays for a GPU readback in the middle of a transition.
    var cachedAmbient by remember(cacheKey) { mutableStateOf(AmbientSnapshots[cacheKey]) }
    val snapshotLayer = rememberGraphicsLayer()
    val layoutDirection = LocalLayoutDirection.current
    LaunchedEffect(cacheKey, coverReady) {
        if (cachedAmbient != null || sceneSize == IntSize.Zero || !coverReady) return@LaunchedEffect
        // Allow layout and the cover painter to reach the recorded ambient layer, and
        // let the enter transition finish before rendering the snapshot.
        withFrameNanos { }
        withFrameNanos { }
        delay(AMBIENT_SNAPSHOT_DELAY_MS)
        try {
            // The ambient is heavily blurred; a third of the resolution is visually identical.
            val small = IntSize(
                (sceneSize.width / AMBIENT_SNAPSHOT_SCALE).coerceAtLeast(1),
                (sceneSize.height / AMBIENT_SNAPSHOT_SCALE).coerceAtLeast(1)
            )
            snapshotLayer.record(density, layoutDirection, small) {
                scale(1f / AMBIENT_SNAPSHOT_SCALE, pivot = Offset.Zero) { drawLayer(ambientLayer) }
            }
            val image = snapshotLayer.toImageBitmap()
            AmbientSnapshots[cacheKey] = image
            cachedAmbient = image
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Keep the live, shared layer if this renderer cannot take a snapshot.
            android.util.Log.w("LiquidGlassScene", "Unable to cache ambient layer", failure)
        }
    }
    val backdrop = if (cachedAmbient != null) cachedBackdrop else ambientBackdrop
    val glassIndication = remember(dark) { GlassIndication(if (dark) Color.White else Color.White.copy(alpha = 0.9f)) }
    val motionScope = rememberCoroutineScope()
    val motion = remember(motionScope) { GlassMotionTracker(motionScope) }
    val parentMotion = LocalLiquidGlassInMotion.current
    val inMotion = remember(motion, parentMotion) { { parentMotion() || motion.active } }
    Box(Modifier.fillMaxSize().onSizeChanged { sceneSize = it }.nestedScroll(motion)) {
        // Capture the ambient layer separately so glass never samples itself.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(colors.surface)) {
            val image = cachedAmbient
            if (image != null) {
                // Replaying a bitmap avoids re-running even a shared RenderEffect
                // for every card. Never include scrolling content in this cache.
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = FilterQuality.Low
                    )
                }
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(colors.tertiary.copy(alpha = if (dark) 0.45f else 0.24f), Color.Transparent),
                            center = Offset(size.width * 0.85f, size.height * 0.12f),
                            radius = size.width * 1.1f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(colors.primary.copy(alpha = if (dark) 0.3f else 0.16f), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.75f),
                            radius = size.width * 1.3f
                        )
                    )
                }
                if (!cover.isNullOrBlank()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = if (dark) 0.28f else 0.18f,
                        modifier = Modifier.fillMaxSize().blur(64.dp),
                        onSuccess = { coverReady = true },
                        onError = { coverReady = true }
                    )
                }
                }
        }
        CompositionLocalProvider(
            LocalLiquidGlassBackdrop provides backdrop,
            LocalLiquidGlassInMotion provides inMotion,
            androidx.compose.foundation.LocalIndication provides glassIndication,
            androidx.compose.material3.LocalContentColor provides colors.onSurface,
            content = content
        )
    }
}

@Composable
internal fun Modifier.liquidGlass(
    shape: Shape = RoundedRectangle(24.dp),
    selected: Boolean = false
): Modifier {
    val backdrop = LocalLiquidGlassBackdrop.current ?: return this
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val surface = if (selected) colors.tertiary.copy(alpha = if (dark) 0.3f else 0.18f)
        else colors.surface.copy(alpha = if (dark) 0.52f else 0.46f)
    val rim = remember(dark) { glassRim(dark) }
    val inMotion = LocalLiquidGlassInMotion.current
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // Static cards need one refraction sample, rather than separate RGB
            // samples. Moving cards drop the lens; this read only invalidates drawing.
            if (!inMotion()) lens(10.dp.toPx(), 18.dp.toPx())
        },
        // The library defaults allocate separate blurred shadow/highlight layers.
        // A cached border supplies the rim without those per-card render passes.
        shadow = null,
        highlight = null,
        onDrawSurface = { drawRect(surface) }
    ).border(0.7.dp, rim, shape)
}

/**
 * Specular rim lit from above, as on iOS 26 glass: a bright top edge that fades through
 * the sides and picks up a little light again at the bottom.
 */
private fun glassRim(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.3f),
        0.45f to Color.White.copy(alpha = 0.05f),
        1f to Color.White.copy(alpha = 0.12f)
    )
} else {
    Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.95f),
        0.45f to Color.Black.copy(alpha = 0.04f),
        1f to Color.Black.copy(alpha = 0.09f)
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accented: Boolean = false,
    height: Dp = 48.dp,
    content: @Composable RowScope.() -> Unit
) {
    val backdrop = checkNotNull(LocalLiquidGlassBackdrop.current)
    val colors = MaterialTheme.colorScheme
    val contentColor = if (accented) colors.onTertiary else colors.onSurface
    CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides contentColor.copy(alpha = if (enabled) 1f else 0.38f)
    ) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            modifier = modifier,
            enabled = enabled,
            preBlurredBackdrop = true,
            tint = if (accented) colors.tertiary else Color.Unspecified,
            surfaceColor = if (accented) Color.Unspecified else colors.surface.copy(alpha = 0.4f),
            height = height,
            content = content
        )
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(48.dp).liquidGlass(Capsule()),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors()
) {
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            modifier = Modifier.liquidGlass(Capsule()),
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent)
        )
    }
}

@Composable
fun GlassSectionHeading(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PlaylistGlassHero(
    title: String,
    cover: String?,
    songCount: Int,
    author: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(title, description) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().padding(16.dp).liquidGlass().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                if (!cover.isNullOrBlank()) {
                    AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!author.isNullOrBlank()) {
                    Text(author, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text(stringResource(R.string.total_songs, songCount), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (!description.isNullOrBlank()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (expanded) R.string.playlist_description_collapse else R.string.playlist_description_expand),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}
