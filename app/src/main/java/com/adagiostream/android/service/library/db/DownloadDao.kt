package com.adagiostream.android.service.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `downloads` table (baw.6.1).
 *
 * Flow queries back the reactive UI (per-track button state + storage screen);
 * suspend queries back the worker/state-machine logic.
 */
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'completed' ORDER BY updatedAt DESC")
    fun observeCompleted(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun getByStatus(status: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('queued', 'downloading', 'paused')")
    suspend fun getActive(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'completed'")
    suspend fun getCompleted(): List<DownloadEntity>

    @Query("UPDATE downloads SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query(
        """
        UPDATE downloads
        SET status = :status,
            resumeOffset = :resumeOffset,
            bytesTotal = :bytesTotal,
            localPath = :localPath,
            error = :error,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        resumeOffset: Long,
        bytesTotal: Long,
        localPath: String?,
        error: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM downloads WHERE status = 'completed'")
    suspend fun completedCount(): Int
}
