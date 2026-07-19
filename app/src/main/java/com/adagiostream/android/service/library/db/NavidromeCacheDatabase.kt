package com.adagiostream.android.service.library.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Navidrome music-library cache + download index (E6),
 * plus the Audiobookshelf offline-download manifest (beads_adagio-59p.1.6).
 *
 * SEPARATE from the JSON [com.adagiostream.android.service.persistence.PersistenceService]
 * (accounts/settings/IPTV), per the ratified baw.1.1 decision.
 *
 * Version 2 adds `audiobook_downloads`. `exportSchema = true` writes the schema
 * JSON under `app/schemas/` so each change ships with a diffable migration.
 * Foreign keys are enabled via the open callback so CASCADE deletes actually
 * fire on SQLite.
 */
@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DownloadEntity::class,
        AudiobookDownloadEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NavidromeCacheDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao
    abstract fun libraryDao(): LibraryDao
    abstract fun audiobookDownloadDao(): AudiobookDownloadDao

    companion object {
        const val DB_NAME = "navidrome_cache.db"

        /** v1 → v2: the audiobook download manifest table (beads_adagio-59p.1.6). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audiobook_downloads` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT,
                        `coverPath` TEXT,
                        `duration` REAL,
                        `status` TEXT NOT NULL,
                        `filesJson` TEXT NOT NULL,
                        `chaptersJson` TEXT NOT NULL,
                        `currentTime` REAL NOT NULL,
                        `error` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audiobook_downloads_status` " +
                        "ON `audiobook_downloads` (`status`)",
                )
            }
        }

        /**
         * Builds the production database. SQLite disables foreign-key enforcement
         * by default per-connection, so the callback turns it on for CASCADE.
         */
        fun build(context: Context): NavidromeCacheDatabase =
            Room.databaseBuilder(context, NavidromeCacheDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
