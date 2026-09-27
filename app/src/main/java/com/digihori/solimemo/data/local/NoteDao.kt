package com.digihori.solimemo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAtEpochMillis IS NULL ORDER BY isPinned ASC, updatedAtEpochMillis ASC")
    fun observeTimeline(): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAtEpochMillis IS NULL
          AND (:query = '' OR title LIKE '%' || :query || '%' OR body LIKE '%' || :query || '%' OR tagsSerialized LIKE '%' || :query || '%')
        ORDER BY isPinned ASC, updatedAtEpochMillis ASC
        """,
    )
    fun observeSearch(query: String): Flow<List<NoteEntity>>

    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAtEpochMillis IS NOT NULL
          AND syncState != 'PENDING_PURGE'
        ORDER BY deletedAtEpochMillis DESC
        """,
    )
    fun observeTrash(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE syncState != 'PENDING_PURGE'")
    fun observeTaggableNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoteEntity?

    @Query(
        """
        SELECT * FROM notes
        WHERE deletedAtEpochMillis IS NULL
          AND title IS :title
          AND body = :body
          AND createdAtEpochMillis >= :createdAfter
        ORDER BY createdAtEpochMillis DESC
        LIMIT 1
        """,
    )
    suspend fun findRecentMatchingNote(title: String?, body: String, createdAfter: Long): NoteEntity?

    @Query(
        """
        SELECT * FROM notes
        WHERE syncState IN ('LOCAL_ONLY', 'PENDING_UPLOAD', 'PENDING_DELETE', 'PENDING_RESTORE', 'PENDING_PURGE', 'SYNC_ERROR')
        ORDER BY updatedAtEpochMillis ASC
        """,
    )
    suspend fun findPendingSync(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun findByDriveFileId(driveFileId: String): NoteEntity?

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<NoteEntity>)

    @Query("SELECT * FROM notes WHERE deletedAtEpochMillis IS NOT NULL")
    suspend fun findDeleted(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE syncState != 'PENDING_PURGE'")
    suspend fun findTaggableNotes(): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)
}
