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
        assertNull(repository.create("  "))

        assertEquals("fixed-id", repository.create(" hello "))
        val note = dao.findById("fixed-id")!!
        assertEquals("hello", note.body)
        assertEquals(SyncState.LOCAL_ONLY, note.syncState)
        assertEquals(1_000L, note.createdAtEpochMillis)
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
        assertEquals(SyncState.PENDING_DELETE, deleted.syncState)
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
        )
    }

    override suspend fun upsert(note: NoteEntity) {
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
}
