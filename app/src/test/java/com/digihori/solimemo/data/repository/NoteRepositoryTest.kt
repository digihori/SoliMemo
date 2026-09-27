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
import com.digihori.solimemo.data.local.tags

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
    fun duplicateCreateRequestWithinShortWindowReusesExistingNote() = runBlocking {
        assertEquals("fixed-id", repository.create("", "shared article"))
        now = 1_001L

        assertEquals("fixed-id", repository.create("", "shared article"))
        assertEquals(1, dao.noteCount())
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

    @Test
    fun pinAndTagsDoNotChangeUpdatedTime() = runBlocking {
        dao.upsert(note(driveFileId = "drive-id", syncState = SyncState.SYNCED))
        now = 9_000L

        repository.setPinned("fixed-id", true)
        repository.setTags("fixed-id", listOf(" 仕事 ", "あとで読む", "仕事"))

        val updated = dao.findById("fixed-id")!!
        assertEquals(true, updated.isPinned)
        assertEquals("仕事\nあとで読む", updated.tagsSerialized)
        assertEquals(500L, updated.updatedAtEpochMillis)
        assertEquals(9_001L, updated.metadataUpdatedAtEpochMillis)
        assertEquals(SyncState.PENDING_UPLOAD, updated.syncState)
    }

    @Test
    fun renamesAndDeletesTagAcrossNotes() = runBlocking {
        dao.upsert(note(driveFileId = "drive-1", syncState = SyncState.SYNCED).copy(tagsSerialized = "仕事\n重要"))
        dao.upsert(
            note(driveFileId = "drive-2", syncState = SyncState.SYNCED).copy(
                id = "second-id",
                tagsSerialized = "仕事\nあとで読む",
            ),
        )

        assertEquals(2, repository.renameTag("仕事", "作業"))
        assertEquals(listOf("作業", "重要"), dao.findById("fixed-id")?.tags())
        assertEquals(listOf("作業", "あとで読む"), dao.findById("second-id")?.tags())
        assertEquals(SyncState.PENDING_UPLOAD, dao.findById("fixed-id")?.syncState)

        assertEquals(2, repository.deleteTag("作業"))
        assertEquals(listOf("重要"), dao.findById("fixed-id")?.tags())
        assertEquals(listOf("あとで読む"), dao.findById("second-id")?.tags())
    }

    @Test
    fun metadataTimestampAlwaysAdvancesEvenWhenStoredTimeIsInTheFuture() = runBlocking {
        dao.upsert(
            note(driveFileId = "drive-id", syncState = SyncState.SYNCED).copy(
                metadataUpdatedAtEpochMillis = 10_000L,
            ),
        )
        now = 9_000L

        repository.setTags("fixed-id", listOf("動画"))

        assertEquals(10_001L, dao.findById("fixed-id")?.metadataUpdatedAtEpochMillis)
        assertEquals(listOf("動画"), dao.findById("fixed-id")?.tags())
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
        values.filter { it.deletedAtEpochMillis == null }
            .sortedWith(compareBy<NoteEntity> { it.isPinned }.thenBy { it.updatedAtEpochMillis })
    }

    override fun observeSearch(query: String): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter {
            it.deletedAtEpochMillis == null &&
                (query.isEmpty() || it.title.orEmpty().contains(query) || it.body.contains(query))
        }.sortedWith(compareBy<NoteEntity> { it.isPinned }.thenBy { it.updatedAtEpochMillis })
    }

    override fun observeTrash(): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter {
            it.deletedAtEpochMillis != null && it.syncState != SyncState.PENDING_PURGE
        }.sortedByDescending { it.deletedAtEpochMillis }
    }

    override fun observeTaggableNotes(): Flow<List<NoteEntity>> = notes.map { values ->
        values.filter { it.syncState != SyncState.PENDING_PURGE }
    }

    override fun observeById(id: String): Flow<NoteEntity?> =
        notes.map { values -> values.firstOrNull { it.id == id } }

    override suspend fun findById(id: String): NoteEntity? = notes.value.firstOrNull { it.id == id }

    override suspend fun findRecentMatchingNote(
        title: String?,
        body: String,
        createdAfter: Long,
    ): NoteEntity? = notes.value
        .filter { it.deletedAtEpochMillis == null && it.title == title && it.body == body && it.createdAtEpochMillis >= createdAfter }
        .maxByOrNull { it.createdAtEpochMillis }

    fun noteCount(): Int = notes.value.size

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

    override suspend fun findTaggableNotes(): List<NoteEntity> =
        notes.value.filter { it.syncState != SyncState.PENDING_PURGE }

    override suspend fun deleteById(id: String) {
        notes.value = notes.value.filterNot { it.id == id }
    }

    override suspend fun upsert(note: NoteEntity) {
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }

    override suspend fun upsertAll(notes: List<NoteEntity>) {
        val ids = notes.mapTo(mutableSetOf(), NoteEntity::id)
        this.notes.value = this.notes.value.filterNot { it.id in ids } + notes
    }
}
