package com.ikunshare.sound.ui.navigation

/**
 * 定义应用中所有页面的路由
 * 
 * 使用密封类确保类型安全的导航
 * 
 * @property route 路由字符串，用于导航
 */
sealed class Screen(val route: String) {

    /**
     * 搜索页面 - 应用的起始页面
     * 提供音乐搜索功能
     */
    data object Search : Screen("search")

    /**
     * 播放页面 - 显示当前播放的歌曲
     * 
     * @param songId 歌曲 ID，用于加载歌曲信息
     */
    data object Player : Screen("player/{songId}") {
        /**
         * 创建带有歌曲 ID 的路由
         * 
         * @param songId 要播放的歌曲 ID
         * @return 完整的路由字符串
         */
        fun createRoute(songId: Long): String = "player/$songId"
    }

    /**
     * 歌单页面 - 显示歌单详情和歌曲列表
     *
     * @param playlistId 歌单 ID，用于加载歌单信息
     */
    data object Playlist : Screen("playlist/{playlistId}") {
        /**
         * 创建带有歌单 ID 的路由
         *
         * @param playlistId 歌单 ID
         * @return 完整的路由字符串
         */
        fun createRoute(playlistId: String): String = "playlist/$playlistId"
    }

    /**
     * 专辑页面 - 显示专辑详情和歌曲列表
     *
     * @param albumKey "source:albumId" 形式的复合 ID
     */
    data object Album : Screen("album/{albumKey}") {
        fun createRoute(albumKey: String): String = "album/$albumKey"
    }

    /**
     * 歌手页面 - 显示歌手详情和热门歌曲列表
     *
     * @param artistKey "source:artistId" 形式的复合 ID
     */
    data object Artist : Screen("artist/{artistKey}") {
        fun createRoute(artistKey: String): String = "artist/$artistKey"
    }

    /**
     * 歌词页面 - 显示同步滚动歌词
     * 与 PlayerViewModel 共享状态
     */
    data object Lyrics : Screen("lyrics")

    /**
     * 本地歌单详情页面 - 显示收藏或最近播放列表
     *
     * @param type 类型参数：favorites 或 recent
     */
    data object LocalPlaylist : Screen("local_playlist/{type}") {
        fun createRoute(type: String): String = "local_playlist/$type"
    }

    /**
     * 下载页面 - 管理离线下载
     */
    data object Download : Screen("download")
}
