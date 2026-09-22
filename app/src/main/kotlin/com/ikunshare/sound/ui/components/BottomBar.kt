package com.ikunshare.sound.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ikunshare.sound.R
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.theme.LocalUiColors
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
fun BottomBarWithMiniPlayer(
    navController: NavController,
    isOnTabs: Boolean,
    showMiniPlayer: Boolean,
    playerViewModel: PlayerViewModel,
    selectedTabIndex: Int,
    onTabClick: (Int) -> Unit,
    isCustomTheme: Boolean,
    bottomBarOpacity: Float
) {
    val playerState by playerViewModel.uiState.collectAsState()
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    val uiColors = LocalUiColors.current
    val barColor = if (isCustomTheme) uiColors.bottomBarColor
    else uiColors.bottomBarColor.copy(alpha = uiColors.bottomBarColor.alpha * bottomBarOpacity)

    val progress by remember {
        derivedStateOf {
            if (playerState.duration > 0L) {
                val raw = currentPosition.toFloat() / playerState.duration.toFloat()
                (raw * 200).toInt() / 200f
            } else 0f
        }
    }

    Column {
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            MiniPlayer(
                song = playerState.currentSong,
                isPlaying = playerState.isPlaying,
                isLoading = playerState.isLoading,
                progress = progress,
                currentLyricText = playerState.currentLyricText,
                onPlayPause = playerViewModel::togglePlayPause,
                onPrevious = playerViewModel::previous,
                onNext = playerViewModel::next,
                onClick = {
                    playerState.currentSong?.let { song ->
                        navController.navigate(Screen.Player.createRoute(song.id)) {
                            launchSingleTop = true
                        }
                    }
                },
                barColor = barColor
            )
        }

        if (isOnTabs) {
            KunyinBottomNavBar(
                selectedIndex = selectedTabIndex,
                onTabClick = onTabClick,
                barColor = barColor
            )
        } else {
            // 没有 NavigationBar 时，手动填充导航栏高度
            // 避免三键导航遮挡 MiniPlayer 和内容
            Spacer(
                Modifier
                    .background(barColor)
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
        }
    }
}

@Composable
private fun KunyinBottomNavBar(
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    barColor: Color = MaterialTheme.colorScheme.surface
) {
    NavigationBar(
        containerColor = barColor,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        bottomNavItems.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onTabClick(index) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.labelRes),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.tertiary,
                    selectedTextColor = MaterialTheme.colorScheme.tertiary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                )
            )
        }
    }
}


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
    val playerState by playerViewModel.uiState.collectAsState()
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    val uiColors = LocalUiColors.current
    val barColor = if (isCustomTheme) uiColors.bottomBarColor
    else uiColors.bottomBarColor.copy(alpha = uiColors.bottomBarColor.alpha * bottomBarOpacity)


    val progress = if (playerState.duration > 0L) {
        (currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val currentTab = bottomNavItems.getOrNull(selectedTabIndex)

    Column {
        // MiniPlayer
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(500)
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(500)
            )
        ) {
            val isInCompactMode = !isWideScreen && isCompactMode && isOnTabs && currentTab != null

            Crossfade(
                targetState = isInCompactMode,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "MiniPlayerModeSwitch"
            ) { compactModeEnabled ->
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
                            navController.navigate(Screen.Player.createRoute(song.id)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    barColor = barColor,
                    backdrop = backdrop,
                    compactMode = compactModeEnabled,
                    isWideScreen = isWideScreen,
                    enableBlur = enableBlur
                )
            }
        }

        // BottomNavBar - 仅在窄屏非紧凑模式下显示
        AnimatedVisibility(
            visible = isOnTabs && !isCompactMode && !isWideScreen,
            enter = expandVertically(
                expandFrom = Alignment.Bottom,
                animationSpec = tween(500)
            ),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = tween(500)
            )
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
    val contentColor = if (isLightTheme) Color.Black else Color.White
    val containerColor = if (enableBlur) {
        if (isLightTheme) Color.White.copy(alpha = 0.3f)
        else Color.Black.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    ColorFilter.tint(contentColor)

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
                        modifier = Modifier.size(28f.dp)
                    )
                }
            }
        }
    }
}