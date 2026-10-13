package com.ikunshare.music.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser
import com.ikunshare.music.platform.kg.kugouStorageId

/** Upgrade all linked keys together; preserve raw provider JSON and the first occurrence in each playlist. */
internal fun migrateKugouIdentity(db: SQLiteDatabase) {
    db.beginTransaction()
    try {
        db.execSQL("CREATE TEMP TABLE kg_id_map (old_id INTEGER PRIMARY KEY, new_id INTEGER NOT NULL)")
        db.execSQL("CREATE TEMP TABLE kg_songs (song_id INTEGER PRIMARY KEY, song_json TEXT NOT NULL)")
        db.rawQuery("SELECT song_id, song_json FROM songs WHERE source = 'kg' ORDER BY rowid", null).use { cursor ->
            while (cursor.moveToNext()) {
                val oldId = cursor.getLong(0)
                val json = cursor.getString(1)
                val newId = runCatching {
                    val obj = JsonParser.parseString(json).asJsonObject
                    fun text(key: String) = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    kugouStorageId(text("hash"), oldId, text("lyricDownloadId"))
                }.getOrDefault(oldId) // Corrupt or hashless rows are retained, never silently dropped.
                db.insertOrThrow("kg_id_map", null, ContentValues().apply { put("old_id", oldId); put("new_id", newId) })
                db.insertWithOnConflict("kg_songs", null, ContentValues().apply {
                    put("song_id", newId); put("song_json", json)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            }
        }
        db.execSQL("""CREATE TEMP TABLE kg_playlist_links (
            playlist_id INTEGER NOT NULL, song_id INTEGER NOT NULL, source TEXT NOT NULL,
            added_at INTEGER NOT NULL, position INTEGER NOT NULL,
            PRIMARY KEY(playlist_id, song_id, source))""")
        db.execSQL("""INSERT OR IGNORE INTO kg_playlist_links
            SELECT ps.playlist_id, COALESCE(m.new_id, ps.song_id), ps.source, ps.added_at, ps.position
            FROM playlist_songs ps LEFT JOIN kg_id_map m ON ps.source = 'kg' AND ps.song_id = m.old_id
            ORDER BY ps.position, ps.added_at, ps.rowid""")
        db.execSQL("""CREATE TEMP TABLE kg_redirects (
            song_id INTEGER NOT NULL, source TEXT NOT NULL, target_song_json TEXT NOT NULL,
            created_at INTEGER NOT NULL, PRIMARY KEY(song_id, source))""")
        db.execSQL("""INSERT OR REPLACE INTO kg_redirects
            SELECT COALESCE(m.new_id, r.song_id), r.source, r.target_song_json, r.created_at
            FROM song_redirects r LEFT JOIN kg_id_map m ON r.source = 'kg' AND r.song_id = m.old_id
            ORDER BY r.created_at, r.rowid""")
        db.execSQL("DELETE FROM songs WHERE source = 'kg'")
        db.execSQL("INSERT INTO songs (song_id, source, song_json) SELECT song_id, 'kg', song_json FROM kg_songs")
        db.execSQL("DELETE FROM playlist_songs")
        db.execSQL("INSERT INTO playlist_songs SELECT * FROM kg_playlist_links")
        db.execSQL("DELETE FROM song_redirects")
        db.execSQL("INSERT INTO song_redirects SELECT * FROM kg_redirects")
        db.execSQL("DROP TABLE kg_redirects")
        db.execSQL("DROP TABLE kg_playlist_links")
        db.execSQL("DROP TABLE kg_songs")
        db.execSQL("DROP TABLE kg_id_map")
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}
