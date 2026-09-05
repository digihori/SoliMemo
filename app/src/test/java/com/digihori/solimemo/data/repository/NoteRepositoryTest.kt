package com.digihori.solimemo.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState

class NoteRepositoryTest {
    private val dao = FakeNoteDao()
    private var now = 1_000L
    private val repository = NoteRepository(dao, { now }, { "fixed-id" })

    @Test
    fun createRejectsBlankAndCreatesLocalOnlyNote() = runBlocking {
        assertNull(repository.create("  ", "  "))

        assertEquals("fixed-id", repository.create("", " hello "))
        val note = dao.findById("fixed-id")!!
        assertEquals("hello", note.body)
        assertEquals(SyncState.LOCAL_ONLY, note.syncState)
        assertEquals(1_000L, note.createdAtEpochMillis)
    }

    @Test
    fun createAcceptsTitleWithoutBody() = runBlocking {
        assertEquals("fixed-id", repository.create("  title  ", ""))

        val note = dao.findById("fixed-id")!!
        assertEquals("title", note.title)
        assertEquals("", note.body)
    }

    @Test
    fun updateNormalizesTitleAndMarksSyncedNotePending() = runBlocking {
        dao.upsert(note(driveFileId = "drive-id", syncState = SyncState.SYNCED))
        now = 2_000L

        repository.update("fixed-id", "  title  ", "updated")

        val updated = dao.findById("fixed-id")!!
        assertEquals("title", updated.title)
        assertEquals("updated", updated.body)
        assertEquals(2_000L, updated.updatedAtEpochMillis)
        assertEquals(SyncState.PENDING_UPLOAD, updated.syncState)
    }

    @Test
    fun deleteUsesLogicalDeletion() = runBlocking {
        dao.upsert(note())
        now = 3_000L

        repository.delete("fixed-id")

        val deleted = dao.findById("fixed-id")!!
        assertEquals(3_000L, deleted.deletedAtEpochMillis)
        assertEquals(500L, deleted.updatedAtEpochMillis)
        assertEquals(SyncState.PENDING_DELETE, deleted.syncState)
    }

    @Test
    fun restoreClearsDeletionAndMarksDriveNotePending() = runBlocking {
        dao.upsert(
            note(driveFileId = "drive-id", syncState = SyncState.SYNCED)
                .copy(deletedAtEpochMillis = 900L),
        )
        now = 4_000L

        repository.restore("fixed-id")

        val restored = dao.findById("fixed-id")!!
        assertNull(restored.deletedAtEpochMillis)
        assertEquals(500L, restored.updatedAtEpochMillis)
        assertEquals(SyncState.PENDING_RESTORE, restored.syncState)
    }

    @Test
    fun purgeMarksDeletedNoteForPermanentDeletion() = runBlocking {
        dao.upsert(note().copy(deletedAtEpochMillis = 900L))

        repository.purge("fixed-id")

        assertEquals(SyncState.PENDING_PURGE, dao.findById("fixed-id")?.syncState)
    }

    private fun note(
        driveFileId: String? = null,
        syncState: SyncState = SyncState.LOCAL_ONLY,
    ) = NoteEntity(
        id = "fixed-id",
        title = null,
        body = "body",
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 500L,
        deletedAtEpochMillis = null,
        syncState = syncState,
        driveFileId = driveFileId,
        driveVersion = null,
        lastSyncError = null,
    )
}

private class FakeNoteDao : NoteDao {
    private val notes = MutableStateFlow<List<NoteEntity>>(emptyList())

    override fun observeTimeline(): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter { it.deletedAtEpochMillis == null }.sortedByDescending { it.updatedAtEpochMillis }
    }

    override fun observeSearch(query: String): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter {
            it.deletedAtEpochMillis == null &&
                (query.isEmpty() || it.title.orEmpty().contains(query) || it.body.contains(query))
        }.sortedByDescending { it.updatedAtEpochMillis }
    }

    override fun observeTrash(): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter {
            it.deletedAtEpochMillis != null && it.syncState != SyncState.PENDING_PURGE
        }.sortedByDescending { it.deletedAtEpochMillis }
    }

    override fun observeById(id: String): Flow<NoteEntity?> =
        notes.map { values -> values.firstOrNull { it.id == id } }

    override suspend fun findById(id: String): NoteEntity? = notes.value.firstOrNull { it.id == id }

    override suspend fun findByDriveFileId(driveFileId: String): NoteEntity? =
        notes.value.firstOrNull { it.driveFileId == driveFileId }

    override suspend fun findPendingSync(): List<NoteEntity> = notes.value.filter {
        it.syncState in setOf(
            SyncState.LOCAL_ONLY,
            SyncState.PENDING_UPLOAD,
            SyncState.PENDING_DELETE,
            SyncState.PENDING_PURGE,
        )
    }

    override suspend fun findDeleted(): List<NoteEntity> =
        notes.value.filter { it.deletedAtEpochMillis != null }

    override suspend fun deleteById(id: String) {
        notes.value = notes.value.filterNot { it.id == id }
    }

    override suspend fun upsert(note: NoteEntity) {
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
}
