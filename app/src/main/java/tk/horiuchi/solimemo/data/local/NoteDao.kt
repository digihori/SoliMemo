package tk.horiuchi.solimemo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE deletedAtEpochMillis IS NULL ORDER BY updatedAtEpochMillis DESC")
    fun observeTimeline(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): NoteEntity?

    @Query(
        """
        SELECT * FROM notes
        WHERE syncState IN ('LOCAL_ONLY', 'PENDING_UPLOAD', 'PENDING_DELETE')
        ORDER BY updatedAtEpochMillis ASC
        """,
    )
    suspend fun findPendingSync(): List<NoteEntity>

    @Upsert
    suspend fun upsert(note: NoteEntity)
}
