package tk.horiuchi.solimemo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["updatedAtEpochMillis"]),
        Index(value = ["syncState"]),
        Index(value = ["driveFileId"], unique = true),
    ],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val body: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val syncState: SyncState,
    val driveFileId: String?,
    val driveVersion: String?,
    val lastSyncError: String?,
)

