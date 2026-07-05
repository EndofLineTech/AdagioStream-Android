package com.adagiostream.android.service.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Navidrome music-library cache + download index (E6).
 *
 * SEPARATE from the JSON [com.adagiostream.android.service.persistence.PersistenceService]
 * (accounts/settings/IPTV), per the ratified baw.1.1 decision.
 *
 * Version 1 — first release. `exportSchema = true` writes the schema JSON under
 * `app/schemas/` so the next change ships with a diffable migration. Foreign keys
 * are enabled via [callback] so CASCADE deletes actually fire on SQLite.
 */
@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NavidromeCacheDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun libraryDao(): LibraryDao

    companion object {
        const val DB_NAME = "navidrome_cache.db"

        /**
         * Builds the production database. SQLite disables foreign-key enforcement
         * by default per-connection, so the callback turns it on for CASCADE.
         */
        fun build(context: Context): NavidromeCacheDatabase =
            Room.databaseBuilder(context, NavidromeCacheDatabase::class.java, DB_NAME)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
