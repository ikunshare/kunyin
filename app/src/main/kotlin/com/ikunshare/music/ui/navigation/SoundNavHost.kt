package com.ikunshare.music.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigationevent.NavigationEvent
import com.ikunshare.music.manager.CredentialManager
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.LocalLiquidGlassInMotion
import com.ikunshare.music.ui.screens.album.AlbumScreen
import com.ikunshare.music.ui.screens.artist.ArtistScreen
import com.ikunshare.music.ui.screens.charts.ChartsScreen
import com.ikunshare.music.ui.screens.discover.DiscoverScreen
import com.ikunshare.music.ui.screens.download.DownloadScreen
import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.screens.playlist.PlaylistScreen
import com.ikunshare.music.ui.screens.playlists.LocalPlaylistScreen
import com.ikunshare.music.ui.screens.playlists.PlaylistsScreen
import com.ikunshare.music.ui.screens.search.SearchScreen
import com.ikunshare.music.ui.screens.settings.KgQRLoginScreen
import com.ikunshare.music.ui.screens.settings.LoginWebViewScreen
import com.ikunshare.music.ui.screens.settings.QRLoginScreen
import com.ikunshare.music.ui.screens.settings.SettingsScreen
import com.ikunshare.music.ui.screens.settings.WyQRLoginScreen
import com.ikunshare.music.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun parallax(fullOffset: Int) = (fullOffset * Motion.PARALLAX_FRACTION).toInt()

private typealias NavTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>

private fun NavTransitionScope.pushEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.Start, Motion.smoothOffset)

private fun NavTransitionScope.pushExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.Start, Motion.smoothOffset, ::parallax) +
        fadeOut(tween(320, easing = Motion.FadeEasing))

private fun NavTransitionScope.popEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.End, Motion.smoothOffset, ::parallax) +
        fadeIn(tween(280, easing = Motion.FadeEasing))

private fun NavTransitionScope.popExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.End, Motion.smoothOffset)

/** The covered page slides back in from the side opposite the swipe's starting edge. */
private fun NavTransitionScope.predictivePopEnter(edge: Int): EnterTransition {
    val direction = if (edge == NavigationEvent.EDGE_RIGHT) SlideDirection.Start else SlideDirection.End
    return slideIntoContainer(direction, Motion.tracking(), ::parallax) + fadeIn(Motion.tracking())
}

/** The top page follows the finger away from the edge the swipe began at. */
private fun NavTransitionScope.predictivePopExit(edge: Int): ExitTransition {
    val direction = if (edge == NavigationEvent.EDGE_RIGHT) SlideDirection.Start else SlideDirection.End
    return slideOutOfContainer(direction, Motion.tracking())
}

/** 底栏标签页的统一路由 */
const val TABS_ROUTE = "tabs"

/**
 * Tab bar taps jump straight to the page (as iOS does) instead of scrolling through
 * every page in between, then fade the new page in. Swipes still use the pager.
 */
@Stable
class TabSwitcher(val pagerState: PagerState, private val scope: CoroutineScope) {
    internal val reveal = Animatable(1f)

    fun select(index: Int) {
        if (index == pagerState.currentPage && !pagerState.isScrollInProgress) return
        scope.launch {
            reveal.snapTo(0f)
            pagerState.scrollToPage(index)
            reveal.animateTo(1f, spring(Spring.DampingRatioNoBouncy, 520f, 0.002f))
        }
    }
}

@Composable
fun rememberTabSwitcher(pagerState: PagerState): TabSwitcher {
    val scope = rememberCoroutineScope()
    return remember(pagerState, scope) { TabSwitcher(pagerState, scope) }
}

@Composable
fun SoundNavHost(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    credentialManager: CredentialManager,
    tabSwitcher: TabSwitcher,
    modifier: Modifier = Modifier
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val pagerState = tabSwitcher.pagerState
    NavHost(
        navController = navController,
        startDestination = TABS_ROUTE,
        modifier = modifier,
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
        predictivePopEnterTransition = { edge -> predictivePopEnter(edge) },
        predictivePopExitTransition = { edge -> predictivePopExit(edge) }
    ) {
        // 底栏标签页 — HorizontalPager
        composable(route = TABS_ROUTE) {
            // Compose only the visible tab while this destination animates in (returning from
            // a detail page rebuilds it); the other pages join once the transition settles.
            var warm by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                snapshotFlow { transition.isRunning }.first { !it }
                warm = true
            }
            // Glass cards skip refraction while pages slide; read lazily in the draw phase.
            val pagerMotion = remember(pagerState) { { pagerState.isScrollInProgress } }
            CompositionLocalProvider(LocalLiquidGlassInMotion provides pagerMotion) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val reveal = tabSwitcher.reveal.value
                            alpha = lerp(0.25f, 1f, reveal)
                            val scale = lerp(0.97f, 1f, reveal)
                            scaleX = scale
                            scaleY = scale
                        },
                    // 4 个主页面全部保活：切到下载页查看进度再返回搜索时，
                    // 搜索结果 / 滚动位置不被销毁重建（page 0 与 page 2 相距 2，
                    // 原 beyondViewportPageCount=1 会处置搜索页导致 ViewModel 重建丢状态）。
                    beyondViewportPageCount = if (warm) 3 else 0
                ) { page ->
                    when (page) {
                        0 -> SearchScreen(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            viewModelFactory = viewModelFactory,
                            onSongClick = { _, song -> playerViewModel.playInTrial(song) }
                        )

                        1 -> PlaylistsScreen(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            viewModelFactory = viewModelFactory
                        )

                        2 -> DownloadScreen(
                            navController = navController,
                            viewModelFactory = viewModelFactory
                        )

                        3 -> SettingsScreen(
                            navController = navController,
                            viewModelFactory = viewModelFactory,
                            isActive = pagerState.currentPage == 3 && currentEntry?.destination?.route == TABS_ROUTE
                        )
                    }
                }
            }
        }

        composable(Screen.Charts.route) {
            ChartsScreen(navController, playerViewModel, viewModelFactory)
        }

        composable(Screen.Discover.route) { DiscoverScreen(navController, viewModelFactory) }

        // The player is a sheet now; keep its routes registered so Android can restore old
        // back stacks, and forward them to the sheet.
        Screen.Player.registeredRoutes.forEach { playerRoute ->
            composable(
                route = playerRoute,
                arguments = listOf(navArgument("songId") { type = NavType.LongType },
                    navArgument("songKey") { type = NavType.StringType; defaultValue = "" }),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) { backStackEntry ->
                val playerSheet = LocalPlayerSheet.current
                LaunchedEffect(backStackEntry.id) {
                    playerSheet.open(
                        backStackEntry.arguments?.getLong("songId") ?: 0L,
                        backStackEntry.arguments?.getString("songKey").orEmpty()
                    )
                    navController.popBackStack(backStackEntry.destination.id, inclusive = true)
                }
            }
        }

        // 歌单详情页面
        composable(
            route = Screen.Playlist.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
            PlaylistScreen(
                navController = navController,
                playlistId = playlistId,
                playerViewModel = playerViewModel,
                viewModelFactory = viewModelFactory
            )
        }

        // 专辑详情页面
        composable(
            route = Screen.Album.route,
            arguments = listOf(navArgument("albumKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val albumKey = backStackEntry.arguments?.getString("albumKey") ?: ""
            AlbumScreen(
                navController = navController,
                albumKey = albumKey,
                playerViewModel = playerViewModel,
                viewModelFactory = viewModelFactory
            )
        }

        // 歌手详情页面
        composable(
            route = Screen.Artist.route,
            arguments = listOf(navArgument("artistKey") { type = NavType.StringType })
        ) { backStackEntry ->
            val artistKey = backStackEntry.arguments?.getString("artistKey") ?: ""
            ArtistScreen(
                navController = navController,
                artistKey = artistKey,
                playerViewModel = playerViewModel,
                viewModelFactory = viewModelFactory
            )
        }

        // 本地歌单详情页面（收藏/最近播放）
        composable(
            route = Screen.LocalPlaylist.route,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val playlistType = backStackEntry.arguments?.getString("type") ?: "favorites"
            LocalPlaylistScreen(
                navController = navController,
                type = playlistType,
                playerViewModel = playerViewModel,
                viewModelFactory = viewModelFactory
            )
        }

        // WebView 登录页面
        composable(
            route = "login_webview/{provider}",
            arguments = listOf(navArgument("provider") { type = NavType.StringType })
        ) { backStackEntry ->
            val provider = backStackEntry.arguments?.getString("provider") ?: ""
            LoginWebViewScreen(
                provider = provider,
                credentialManager = credentialManager,
                navController = navController
            )
        }

        // QQ音乐扫码登录
        composable(route = "qq_qr_login") {
            QRLoginScreen(
                credentialManager = credentialManager,
                navController = navController
            )
        }

        // 酷狗音乐扫码登录
        composable(route = "kg_qr_login") {
            KgQRLoginScreen(
                credentialManager = credentialManager,
                navController = navController
            )
        }

        // 网易云音乐扫码登录
        composable(route = "wy_qr_login") {
            WyQRLoginScreen(
                credentialManager = credentialManager,
                navController = navController
            )
        }
    }
}
