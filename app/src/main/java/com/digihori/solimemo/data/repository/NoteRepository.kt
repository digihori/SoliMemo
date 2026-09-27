package com.digihori.solimemo.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState
import com.digihori.solimemo.data.local.encodeTags
import com.digihori.solimemo.data.local.normalizeTags
import com.digihori.solimemo.data.local.tags
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DUPLICATE_CREATE_WINDOW_MILLIS = 5_000L

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
    private val createMutex = Mutex()

    fun observeNotes(query: String): Flow<List<NoteEntity>> = noteDao.observeSearch(query.trim())

    fun observeNote(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    fun observeTrash(): Flow<List<NoteEntity>> = noteDao.observeTrash()

    fun observeTaggableNotes(): Flow<List<NoteEntity>> = noteDao.observeTaggableNotes()

    suspend fun hasPendingChanges(): Boolean = noteDao.findPendingSync().isNotEmpty()

    suspend fun create(title: String, body: String): String? = createMutex.withLock {
        val normalizedTitle = title.trim().ifEmpty { null }
        val normalizedBody = body.trim()
        if (normalizedTitle == null && normalizedBody.isEmpty()) return@withLock null
        val now = currentTimeMillis()
        val existing = noteDao.findRecentMatchingNote(
            normalizedTitle,
            normalizedBody,
            now - DUPLICATE_CREATE_WINDOW_MILLIS,
        )
        if (existing != null) return@withLock existing.id
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
                metadataUpdatedAtEpochMillis = now,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
        id
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
                isPinned = false,
                syncState = SyncState.PENDING_DELETE,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        val existing = noteDao.findById(id) ?: return
        if (existing.isPinned == pinned) return
        noteDao.upsert(
            existing.copy(
                isPinned = pinned,
                metadataUpdatedAtEpochMillis = nextMetadataTimestamp(existing),
                syncState = if (existing.driveFileId == null) SyncState.LOCAL_ONLY else SyncState.PENDING_UPLOAD,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun setTags(id: String, tags: List<String>) {
        val existing = noteDao.findById(id) ?: return
        val serialized = encodeTags(normalizeTags(tags))
        if (existing.tagsSerialized == serialized) return
        noteDao.upsert(
            existing.copy(
                tagsSerialized = serialized,
                metadataUpdatedAtEpochMillis = nextMetadataTimestamp(existing),
                syncState = if (existing.driveFileId == null) SyncState.LOCAL_ONLY else SyncState.PENDING_UPLOAD,
                lastSyncError = null,
            ),
        )
        mutableLocalChanges.tryEmit(Unit)
    }

    suspend fun renameTag(oldTag: String, newTag: String): Int {
        val normalizedNewTag = normalizeTags(listOf(newTag)).singleOrNull() ?: return 0
        if (oldTag == normalizedNewTag) return 0
        return updateTagAcrossNotes(oldTag) { tags ->
            tags.map { if (it == oldTag) normalizedNewTag else it }
        }
    }

    suspend fun deleteTag(tag: String): Int = updateTagAcrossNotes(tag) { tags ->
        tags.filterNot { it == tag }
    }

    private suspend fun updateTagAcrossNotes(
        targetTag: String,
        transform: (List<String>) -> List<String>,
    ): Int {
        val affected = noteDao.findTaggableNotes().filter { targetTag in it.tags() }
        val latestExistingTimestamp = affected.maxOfOrNull(NoteEntity::metadataUpdatedAtEpochMillis) ?: 0L
        val metadataUpdatedAt = maxOf(currentTimeMillis(), latestExistingTimestamp.safelyIncrement())
        noteDao.upsertAll(
            affected.map { note ->
                note.copy(
                    tagsSerialized = encodeTags(transform(note.tags())),
                    metadataUpdatedAtEpochMillis = metadataUpdatedAt,
                    syncState = if (note.driveFileId == null) SyncState.LOCAL_ONLY else SyncState.PENDING_UPLOAD,
                    lastSyncError = null,
                )
            },
        )
        if (affected.isNotEmpty()) mutableLocalChanges.tryEmit(Unit)
        return affected.size
    }

    private fun nextMetadataTimestamp(note: NoteEntity): Long =
        maxOf(currentTimeMillis(), note.metadataUpdatedAtEpochMillis.safelyIncrement())

    private fun Long.safelyIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

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
