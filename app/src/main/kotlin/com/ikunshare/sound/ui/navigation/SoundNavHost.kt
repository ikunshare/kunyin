package com.ikunshare.sound.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.DownloadManager
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.screens.album.AlbumScreen
import com.ikunshare.sound.ui.screens.artist.ArtistScreen
import com.ikunshare.sound.ui.screens.download.DownloadScreen
import com.ikunshare.sound.ui.screens.player.PlayerScreen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.screens.playlist.PlaylistScreen
import com.ikunshare.sound.ui.screens.playlists.LocalPlaylistScreen
import com.ikunshare.sound.ui.screens.playlists.PlaylistsScreen
import com.ikunshare.sound.ui.screens.search.SearchScreen
import com.ikunshare.sound.ui.screens.settings.KgQRLoginScreen
import com.ikunshare.sound.ui.screens.settings.LoginWebViewScreen
import com.ikunshare.sound.ui.screens.settings.QRLoginScreen
import com.ikunshare.sound.ui.screens.settings.SettingsScreen
import com.ikunshare.sound.ui.screens.settings.WyQRLoginScreen

/**
 * 导航动画持续时间（毫秒）
 * 根据 Requirements 5.8: 页面切换动画应为 200-300ms
 */
private const val NAVIGATION_ANIMATION_DURATION_MS = 250
private const val PLAYER_ANIMATION_DURATION_MS = 300

/** 底栏标签页的统一路由 */
const val TABS_ROUTE = "tabs"

@Composable
fun SoundNavHost(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    credentialManager: CredentialManager,
    downloadManager: DownloadManager,
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = TABS_ROUTE,
        modifier = modifier,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
            ) + fadeIn(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
            ) + fadeOut(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
            ) + fadeIn(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
            ) + fadeOut(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
        }
    ) {
        // 底栏标签页 — HorizontalPager
        composable(
            route = TABS_ROUTE,
            popEnterTransition = {
                if (initialState.destination.route?.startsWith("player") == true) {
                    fadeIn(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
                } else {
                    slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
                    ) + fadeIn(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
                }
            },
            exitTransition = {
                if (targetState.destination.route?.startsWith("player") == true) {
                    fadeOut(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
                } else {
                    slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS)
                    ) + fadeOut(animationSpec = tween(NAVIGATION_ANIMATION_DURATION_MS))
                }
            }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 4 个主页面全部保活：切到下载页查看进度再返回搜索时，
                // 搜索结果 / 滚动位置不被销毁重建（page 0 与 page 2 相距 2，
                // 原 beyondViewportPageCount=1 会处置搜索页导致 ViewModel 重建丢状态）。
                beyondViewportPageCount = 3
            ) { page ->
                when (page) {
                    0 -> SearchScreen(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        viewModelFactory = viewModelFactory,
                        onSongClick = { _, song ->
                            val store = viewModelFactory.localMusicStore
                            val atHead =
                                viewModelFactory.appSettingsManager.settings.value.trialListAddToHead
                            store.addToTrial(song, atHead)
                            // 整个试听列表作为播放队列；以刚刚加入的那首作为起点。
                            // 由于 addToTrial 是异步写库，先用「当前列表 + 新歌」组装一个乐观队列。
                            val current = store.queryTrialSongs().toMutableList()
                            val key = "${song.id}_${song.getTypeDiscriminator()}"
                            current.removeAll { "${it.id}_${it.getTypeDiscriminator()}" == key }
                            if (atHead) current.add(0, song) else current.add(song)
                            val targetIndex =
                                current.indexOfFirst {
                                    "${it.id}_${it.getTypeDiscriminator()}" == key
                                }.coerceAtLeast(0)
                            playerViewModel.setPlaylistAndPlay(current, targetIndex)
                        }
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
                        viewModelFactory = viewModelFactory
                    )
                }
            }
        }

        // 播放页面
        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            enterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(
                        PLAYER_ANIMATION_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
            },
            exitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(
                        PLAYER_ANIMATION_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
            },
            popEnterTransition = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(
                        PLAYER_ANIMATION_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
            },
            popExitTransition = {
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(
                        PLAYER_ANIMATION_DURATION_MS,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(animationSpec = tween(PLAYER_ANIMATION_DURATION_MS))
            }
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: 0L
            PlayerScreen(
                navController = navController,
                songId = songId,
                viewModel = playerViewModel,
                viewModelFactory = viewModelFactory,
                downloadManager = downloadManager,
                appSettingsManager = viewModelFactory.appSettingsManager
            )
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
