package com.ikunshare.music.ui.components

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.graphics.TransformOrigin
import com.ikunshare.music.ui.theme.Motion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ikunshare.music.R
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.theme.LocalUiColors
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 独立容器组件，用于渲染超出范围的内容
 */
@Composable
fun Block(content: @Composable () -> Unit) {
    content()
}

/**
 * 底部导航栏项定义
 */
data class BottomNavItem(
    @StringRes val labelRes: Int,
    val icon: ImageVector
)

/**
 * 底部导航栏项列表（顺序与 HorizontalPager 页码对应）
 */
val bottomNavItems = listOf(
    BottomNavItem(labelRes = R.string.nav_search, icon = Icons.Filled.Search),
    BottomNavItem(labelRes = R.string.nav_playlists, icon = Icons.AutoMirrored.Filled.QueueMusic),
    BottomNavItem(labelRes = R.string.nav_download, icon = Icons.Filled.Download),
    BottomNavItem(labelRes = R.string.nav_settings, icon = Icons.Filled.Settings)
)


@Composable
fun LiquidGlassBottomBar(
    navController: NavController,
    isOnTabs: Boolean,
    showMiniPlayer: Boolean,
    playerViewModel: PlayerViewModel,
    selectedTabIndex: Int,
    onTabClick: (Int) -> Unit,
    isCustomTheme: Boolean,
    bottomBarOpacity: Float,
    onExitCompactMode: () -> Unit,
    isCompactMode: Boolean = false,
    backdrop: LayerBackdrop,
    isWideScreen: Boolean = false,
    enableBlur: Boolean = true
) {
    val playerSheet = LocalPlayerSheet.current
    val playerState by playerViewModel.uiState.collectAsState()
    val progress = rememberPlaybackProgress(playerViewModel)
    val uiColors = LocalUiColors.current
    val barColor = if (isCustomTheme) uiColors.bottomBarColor
    else uiColors.bottomBarColor.copy(alpha = uiColors.bottomBarColor.alpha * bottomBarOpacity)

    val currentTab = bottomNavItems.getOrNull(selectedTabIndex)

    Column {
        // MiniPlayer
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = expandVertically(Motion.smoothSize, expandFrom = Alignment.Top) +
                fadeIn(Motion.fadeIn()) + scaleIn(Motion.smooth(), initialScale = 0.92f),
            exit = shrinkVertically(Motion.smoothSize, shrinkTowards = Alignment.Top) +
                fadeOut(Motion.fadeOut()) + scaleOut(Motion.smooth(), targetScale = 0.92f)
        ) {
            // One glass surface that morphs between modes; a crossfade would draw two.
            val compactModeEnabled = !isWideScreen && isCompactMode && isOnTabs && currentTab != null
            LiquidGlassMiniPlayer(
                song = playerState.currentSong,
                isPlaying = playerState.isPlaying,
                isLoading = playerState.isLoading,
                progress = progress,
                currentLyricText = playerState.currentLyricText,
                compactIcon = if (compactModeEnabled) currentTab?.icon else null,
                compactLabel = if (compactModeEnabled) currentTab?.labelRes?.let {
                    stringResource(
                        it
                    )
                } else null,
                onPlayPause = playerViewModel::togglePlayPause,
                onPrevious = playerViewModel::previous,
                onNext = playerViewModel::next,
                onCompactExit = if (compactModeEnabled) onExitCompactMode else null,
                onClick = {
                    playerState.currentSong?.let { song ->
                        playerSheet.open(song)
                    }
                },
                barColor = barColor,
                backdrop = backdrop,
                compactMode = compactModeEnabled,
                isWideScreen = isWideScreen,
                enableBlur = enableBlur
            )
        }

        // BottomNavBar - 仅在窄屏非紧凑模式下显示
        AnimatedVisibility(
            visible = isOnTabs && !isCompactMode && !isWideScreen,
            enter = expandVertically(Motion.smoothSize, expandFrom = Alignment.Bottom) +
                fadeIn(Motion.fadeIn(delayMillis = 60)) +
                scaleIn(Motion.smooth(), initialScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 1f)),
            exit = shrinkVertically(Motion.smoothSize, shrinkTowards = Alignment.Bottom) +
                fadeOut(Motion.fadeOut()) +
                scaleOut(Motion.smooth(), targetScale = 0.86f, transformOrigin = TransformOrigin(0.5f, 1f))
        ) {
            LiquidGlassBottomNavBar(
                selectedIndex = selectedTabIndex,
                onTabClick = onTabClick,
                barColor = barColor,
                backdrop = backdrop,
                enableBlur = enableBlur
            )
        }
    }
}

@Composable
private fun LiquidGlassBottomNavBar(
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    barColor: Color = MaterialTheme.colorScheme.surface,
    backdrop: LayerBackdrop,
    enableBlur: Boolean = true
) {
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val accentColor = MaterialTheme.colorScheme.tertiary
    val containerColor = if (enableBlur) {
        if (isLightTheme) Color.White.copy(alpha = 0.3f)
        else Color.Black.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    var currentSelectedIndex by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) {
        currentSelectedIndex = selectedIndex
    }

    Block {
        LiquidBottomTabs(
            selectedTabIndex = { currentSelectedIndex },
            onTabSelected = { index ->
                currentSelectedIndex = index
                onTabClick(index)
            },
            backdrop = backdrop,
            tabsCount = bottomNavItems.size,
            accentColor = accentColor,
            containerColor = containerColor,
            enableBlur = enableBlur,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .padding(bottom = 12.dp)
        ) {
            bottomNavItems.forEachIndexed { index, item ->
                LiquidBottomTab(
                    onClick = { onTabClick(index) }
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                        tint = if (currentSelectedIndex == index) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28f.dp)
                            .padding(top = 4f.dp)
                    )
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentSelectedIndex == index) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2f.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidGlassSideNavBar(
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    enableBlur: Boolean = true
) {
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val accentColor = MaterialTheme.colorScheme.tertiary
    val containerColor = if (enableBlur) {
        if (isLightTheme) Color.White.copy(alpha = 0.3f)
        else Color.Black.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    var currentSelectedIndex by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(selectedIndex) {
        currentSelectedIndex = selectedIndex
    }

    Block {
        LiquidSideTabs(
            selectedTabIndex = { currentSelectedIndex },
            onTabSelected = { index ->
                currentSelectedIndex = index
                onTabClick(index)
            },
            backdrop = backdrop,
            tabsCount = bottomNavItems.size,
            accentColor = accentColor,
            containerColor = containerColor,
            enableBlur = enableBlur,
            modifier = modifier
                .fillMaxHeight()
                .padding(vertical = 36.dp)
                .padding(start = 12.dp)
        ) {
            bottomNavItems.forEachIndexed { index, item ->
                LiquidSideTab(
                    onClick = { onTabClick(index) }
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                        tint = if (currentSelectedIndex == index) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
/**
 * Playback progress as a lazily-read lambda: position ticks then only redraw the
 * progress fill instead of recomposing the whole bar and mini player.
 */
@Composable
private fun rememberPlaybackProgress(playerViewModel: PlayerViewModel): () -> Float {
    val position = playerViewModel.currentPosition.collectAsState()
    val state = playerViewModel.uiState.collectAsState()
    return remember(position, state) {
        {
            val duration = state.value.duration
            if (duration > 0L) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
        }
    }
}
