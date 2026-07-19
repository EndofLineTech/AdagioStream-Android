package com.adagiostream.android.service.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `audiobook_downloads` manifest table (beads_adagio-59p.1.6).
 *
 * Flow queries back the reactive UI (download button + storage screen);
 * suspend queries back the worker and offline-playback lookups.
 */
@Dao
interface AudiobookDownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AudiobookDownloadEntity)

    @Query("SELECT * FROM audiobook_downloads WHERE id = :id")
    suspend fun getById(id: String): AudiobookDownloadEntity?

    @Query("SELECT * FROM audiobook_downloads WHERE id = :id")
    fun observeById(id: String): Flow<AudiobookDownloadEntity?>

    @Query("SELECT * FROM audiobook_downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AudiobookDownloadEntity>>

    @Query("SELECT * FROM audiobook_downloads")
    suspend fun getAll(): List<AudiobookDownloadEntity>

    @Query("UPDATE audiobook_downloads SET currentTime = :seconds, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCurrentTime(id: String, seconds: Double, updatedAt: Long)

    @Query("DELETE FROM audiobook_downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
