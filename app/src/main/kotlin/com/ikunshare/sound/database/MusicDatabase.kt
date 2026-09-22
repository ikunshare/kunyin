package com.ikunshare.sound.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MusicDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kunyin_music.db"
        private const val DATABASE_VERSION = 6

        const val TABLE_SONGS = "songs"
        const val TABLE_PLAYLISTS = "playlists"
        const val TABLE_PLAYLIST_SONGS = "playlist_songs"
        const val TABLE_SONG_REDIRECTS = "song_redirects"

        const val COL_SONG_ID = "song_id"
        const val COL_SOURCE = "source"
        const val COL_SONG_JSON = "song_json"
        const val COL_ADDED_AT = "added_at"
        const val COL_PLAYLIST_ID = "playlist_id"
        const val COL_NAME = "name"
        const val COL_CREATED_AT = "created_at"
        const val COL_POSITION = "position"
        const val COL_REMOTE_SOURCE = "remote_source"
        const val COL_REMOTE_ID = "remote_id"
        const val COL_AUTO_REFRESH = "auto_refresh"
        const val COL_IS_SYSTEM = "is_system"
        const val COL_SYSTEM_KIND = "system_kind"

        const val COL_TARGET_SONG_JSON = "target_song_json"

        const val SYSTEM_KIND_TRIAL = "trial"
        const val SYSTEM_KIND_FAVORITES = "favorites"
        const val SYSTEM_TRIAL_NAME = "试听列表"
        const val SYSTEM_FAVORITES_NAME = "我的收藏"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SONGS (
                $COL_SONG_ID INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_SONG_JSON TEXT NOT NULL,
                PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
            )
        """
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLISTS (
                $COL_PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_REMOTE_SOURCE TEXT,
                $COL_REMOTE_ID TEXT,
                $COL_AUTO_REFRESH INTEGER NOT NULL DEFAULT 0,
                $COL_IS_SYSTEM INTEGER NOT NULL DEFAULT 0,
                $COL_SYSTEM_KIND TEXT
            )
        """
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_PLAYLIST_SONGS (
                $COL_PLAYLIST_ID INTEGER NOT NULL,
                $COL_SONG_ID INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_ADDED_AT INTEGER NOT NULL,
                $COL_POSITION INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY($COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE)
            )
        """
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_SONG_REDIRECTS (
                $COL_SONG_ID INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_TARGET_SONG_JSON TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
            )
        """
        )

        // 创建两个系统歌单：试听列表（在前）+ 我的收藏（在后）
        seedSystemPlaylists(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createPlaylistTablesV2(db)
        }
        if (oldVersion < 3) {
            migrateToSongsTable(db)
        }
        if (oldVersion < 4) {
            addPlaylistRemoteColumns(db)
        }
        if (oldVersion < 5) {
            migrateFavoritesToSystemPlaylists(db)
        }
        if (oldVersion < 6) {
            createSongRedirectsTable(db)
        }
    }

    /** V1 → V2 */
    private fun createPlaylistTablesV2(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                $COL_PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_SONGS (
                $COL_PLAYLIST_ID INTEGER NOT NULL,
                $COL_SONG_ID INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_SONG_JSON TEXT NOT NULL,
                $COL_ADDED_AT INTEGER NOT NULL,
                $COL_POSITION INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY($COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE)
            )
        """
        )
    }

    /** V2 → V3 */
    private fun migrateToSongsTable(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SONGS (
                    $COL_SONG_ID INTEGER NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_SONG_JSON TEXT NOT NULL,
                    PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
                )
            """
            )

            db.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_SONGS ($COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON)
                SELECT $COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON FROM favorites
            """
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_SONGS ($COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON)
                SELECT $COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON FROM recent_plays
            """
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO $TABLE_SONGS ($COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON)
                SELECT $COL_SONG_ID, $COL_SOURCE, $COL_SONG_JSON FROM $TABLE_PLAYLIST_SONGS
            """
            )

            db.execSQL("ALTER TABLE favorites RENAME TO _favorites_old")
            db.execSQL(
                """
                CREATE TABLE favorites (
                    $COL_SONG_ID INTEGER NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_ADDED_AT INTEGER NOT NULL,
                    PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
                )
            """
            )
            db.execSQL(
                """
                INSERT INTO favorites ($COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT)
                SELECT $COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT FROM _favorites_old
            """
            )
            db.execSQL("DROP TABLE _favorites_old")

            db.execSQL("ALTER TABLE recent_plays RENAME TO _recent_plays_old")
            db.execSQL(
                """
                CREATE TABLE recent_plays (
                    $COL_SONG_ID INTEGER NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    played_at INTEGER NOT NULL,
                    PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
                )
            """
            )
            db.execSQL(
                """
                INSERT INTO recent_plays ($COL_SONG_ID, $COL_SOURCE, played_at)
                SELECT $COL_SONG_ID, $COL_SOURCE, played_at FROM _recent_plays_old
            """
            )
            db.execSQL("DROP TABLE _recent_plays_old")

            db.execSQL("ALTER TABLE $TABLE_PLAYLIST_SONGS RENAME TO _playlist_songs_old")
            db.execSQL(
                """
                CREATE TABLE $TABLE_PLAYLIST_SONGS (
                    $COL_PLAYLIST_ID INTEGER NOT NULL,
                    $COL_SONG_ID INTEGER NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_ADDED_AT INTEGER NOT NULL,
                    $COL_POSITION INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY($COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE)
                )
            """
            )
            db.execSQL(
                """
                INSERT INTO $TABLE_PLAYLIST_SONGS (
                    $COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT, $COL_POSITION
                )
                SELECT $COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT, $COL_POSITION
                FROM _playlist_songs_old
            """
            )
            db.execSQL("DROP TABLE _playlist_songs_old")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** V3 → V4 */
    private fun addPlaylistRemoteColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_REMOTE_SOURCE TEXT")
        db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_REMOTE_ID TEXT")
        db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_AUTO_REFRESH INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * V4 → V5: 引入系统歌单，将旧 favorites 表数据迁入「我的收藏」系统歌单，
     * 然后丢弃 favorites / recent_plays 表。
     */
    private fun migrateFavoritesToSystemPlaylists(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_IS_SYSTEM INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PLAYLISTS ADD COLUMN $COL_SYSTEM_KIND TEXT")

            seedSystemPlaylists(db)

            val favoritesId = db.rawQuery(
                "SELECT $COL_PLAYLIST_ID FROM $TABLE_PLAYLISTS WHERE $COL_SYSTEM_KIND = ?",
                arrayOf(SYSTEM_KIND_FAVORITES)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

            if (favoritesId > 0) {
                val cur = db.rawQuery(
                    "SELECT $COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT FROM favorites ORDER BY $COL_ADDED_AT ASC",
                    null
                )
                cur.use {
                    var position = 0
                    while (it.moveToNext()) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO $TABLE_PLAYLIST_SONGS " +
                                    "($COL_PLAYLIST_ID, $COL_SONG_ID, $COL_SOURCE, $COL_ADDED_AT, $COL_POSITION) " +
                                    "VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any>(
                                favoritesId,
                                it.getLong(0),
                                it.getString(1),
                                it.getLong(2),
                                position++
                            )
                        )
                    }
                }
            }

            db.execSQL("DROP TABLE IF EXISTS favorites")
            db.execSQL("DROP TABLE IF EXISTS recent_plays")

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 创建两个系统歌单（如果不存在）。
     * 顺序约定：试听列表 createdAt=2（更新），我的收藏 createdAt=1，
     * 使用 ORDER BY is_system DESC, created_at DESC 可让试听列表排在最前。
     */
    private fun seedSystemPlaylists(db: SQLiteDatabase) {
        val trialExists = db.rawQuery(
            "SELECT 1 FROM $TABLE_PLAYLISTS WHERE $COL_SYSTEM_KIND = ? LIMIT 1",
            arrayOf(SYSTEM_KIND_TRIAL)
        ).use { it.moveToFirst() }
        if (!trialExists) {
            db.execSQL(
                "INSERT INTO $TABLE_PLAYLISTS ($COL_NAME, $COL_CREATED_AT, $COL_AUTO_REFRESH, $COL_IS_SYSTEM, $COL_SYSTEM_KIND) " +
                        "VALUES (?, 2, 0, 1, ?)",
                arrayOf<Any>(SYSTEM_TRIAL_NAME, SYSTEM_KIND_TRIAL)
            )
        }

        val favoritesExists = db.rawQuery(
            "SELECT 1 FROM $TABLE_PLAYLISTS WHERE $COL_SYSTEM_KIND = ? LIMIT 1",
            arrayOf(SYSTEM_KIND_FAVORITES)
        ).use { it.moveToFirst() }
        if (!favoritesExists) {
            db.execSQL(
                "INSERT INTO $TABLE_PLAYLISTS ($COL_NAME, $COL_CREATED_AT, $COL_AUTO_REFRESH, $COL_IS_SYSTEM, $COL_SYSTEM_KIND) " +
                        "VALUES (?, 1, 0, 1, ?)",
                arrayOf<Any>(SYSTEM_FAVORITES_NAME, SYSTEM_KIND_FAVORITES)
            )
        }
    }

    /** V5 → V6: 新增本地歌曲重定向表，允许把歌词/封面来源指向其他平台的歌曲。 */
    private fun createSongRedirectsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SONG_REDIRECTS (
                $COL_SONG_ID INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_TARGET_SONG_JSON TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                PRIMARY KEY($COL_SONG_ID, $COL_SOURCE)
            )
        """
        )
    }
}
