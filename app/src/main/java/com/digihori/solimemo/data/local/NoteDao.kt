package com.digihori.solimemo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAtEpochMillis IS NULL ORDER BY updatedAtEpochMillis DESC")
    fun observeTimeline(): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAtEpochMillis IS NULL
          AND (:query = '' OR title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%')
        ORDER BY updatedAtEpochMillis DESC
        """,
    )
    fun observeSearch(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoteEntity?

    @Query(
        """
        SELECT * FROM notes
        WHERE syncState IN ('LOCAL_ONLY', 'PENDING_UPLOAD', 'PENDING_DELETE', 'SYNC_ERROR')
        ORDER BY updatedAtEpochMillis ASC
        """,
    )
    suspend fun findPendingSync(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun findByDriveFileId(driveFileId: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)
}
