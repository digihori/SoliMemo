package com.digihori.solimemo.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState
import java.util.UUID

class NoteRepository(
    private val noteDao: NoteDao,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutableLocalChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val localChanges = mutableLocalChanges.asSharedFlow()

    fun observeNotes(query: String): Flow<List<NoteEntity>> = noteDao.observeSearch(query.trim())

    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()

    suspend fun hasPendingChanges(): Boolean = noteDao.findPendingSync().isNotEmpty()

    suspend fun create(title: String, body: String): String? {
        val normalizedTitle = title.trim().ifEmpty { null }
        val normalizedBody = body.trim()
        if (normalizedTitle == null && normalizedBody.isEmpty()) return null
        val now = currentTimeMillis()
        val id = newId()
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = normalizedTitle,
                body = normalizedBody,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                deletedAtEpochMillis = null,
                syncState = SyncState.LOCAL_ONLY,
                driveFileId = null,
                driveVersion = null,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
        return id
    }

    suspend fun update(id: String, title: String, body: String) {
        val existing = noteDao.findById(id) ?: return
        val normalizedTitle = title.trim().ifEmpty { null }
        if (normalizedTitle == existing.title && body == existing.body) return
        noteDao.upsert(
            existing.copy(
                title = normalizedTitle,
                body = body,
                updatedAtEpochMillis = currentTimeMillis(),
                syncState = if (existing.driveFileId == null) {
                    SyncState.LOCAL_ONLY
                } else {
                    SyncState.PENDING_UPLOAD
                },
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun delete(id: String) {
        val existing = noteDao.findById(id) ?: return
        val now = currentTimeMillis()
        noteDao.upsert(
            existing.copy(
                deletedAtEpochMillis = now,
                syncState = SyncState.PENDING_DELETE,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun restore(id: String) {
        val existing = noteDao.findById(id) ?: return
        noteDao.upsert(
            existing.copy(
                deletedAtEpochMillis = null,
                syncState = if (existing.driveFileId == null) SyncState.LOCAL_ONLY else SyncState.PENDING_RESTORE,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun purge(id: String) {
        val existing = noteDao.findById(id) ?: return
        noteDao.upsert(existing.copy(syncState = SyncState.PENDING_PURGE, lastSyncError = null))
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun emptyTrash() {
        val deleted = noteDao.findDeleted()
        if (deleted.isEmpty()) return
        deleted.forEach { note ->
            noteDao.upsert(note.copy(syncState = SyncState.PENDING_PURGE, lastSyncError = null))
        }
        mutableLocalChanges.tryEmit(Unit)
    }
}
