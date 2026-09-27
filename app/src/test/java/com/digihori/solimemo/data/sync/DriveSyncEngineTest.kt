package com.digihori.solimemo.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState
import com.digihori.solimemo.data.local.tags
import com.digihori.solimemo.data.remote.DriveDataSource
import com.digihori.solimemo.data.remote.DriveDownloadedFile
import com.digihori.solimemo.data.remote.DriveFileMetadata
import com.digihori.solimemo.data.remote.MarkdownNote
import com.digihori.solimemo.data.remote.MarkdownNoteCodec

class DriveSyncEngineTest {
    @Test
    fun uploadsLocalNoteAndMarksItSynced() = runBlocking {
        val dao = SyncFakeDao()
        dao.upsert(note().copy(isPinned = true, tagsSerialized = "仕事\nあとで読む"))
        val drive = SyncFakeDrive()

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(1, result.uploaded)
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals("drive-local", dao.findById("local")?.driveFileId)
        val remote = drive.downloadNoteFile(drive.listMarkdownFiles().single())
        val decoded = MarkdownNoteCodec.decode(remote.content)
        assertEquals(true, decoded.isPinned)
        assertEquals(listOf("仕事", "あとで読む"), decoded.tags)
    }

    @Test
    fun importsDriveOnlyNote() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        drive.addRemote(
            MarkdownNote("remote", "title", "body", 100, 200, null),
            DriveFileMetadata("drive-remote", "remote.md", "1", null),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(1, result.downloaded)
        assertEquals("body", dao.findById("remote")?.body)
        assertEquals(SyncState.SYNCED, dao.findById("remote")?.syncState)
    }

    @Test
    fun duplicateDriveFilesWithSameNameAreImportedOnlyOnce() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        drive.addRemote(
            MarkdownNote("same-id", null, "old", 100, 100, null),
            DriveFileMetadata("drive-old", "same-id.md", "1", "2026-01-01T00:00:00.000Z"),
        )
        drive.addRemote(
            MarkdownNote("same-id", null, "new", 100, 200, null),
            DriveFileMetadata("drive-new", "same-id.md", "1", "2026-01-02T00:00:00.000Z"),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(1, result.downloaded)
        assertEquals("new", dao.findById("same-id")?.body)
        assertEquals("drive-new", dao.findById("same-id")?.driveFileId)
    }

    @Test
    fun failedUploadDoesNotOverwriteLocalChangesWithKnownDriveVersion() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val remote = MarkdownNote("local", null, "old", 100, 100, null)
        val metadata = DriveFileMetadata("drive-local", "local.md", "1", null)
        drive.addRemote(remote, metadata)
        drive.failUpdates = true
        dao.upsert(
            note().copy(
                body = "local change",
                syncState = SyncState.PENDING_UPLOAD,
                driveFileId = metadata.id,
                driveVersion = metadata.version,
            ),
        )

        DriveSyncEngine(dao, drive).synchronize()

        assertEquals("local change", dao.findById("local")?.body)
        assertEquals(SyncState.SYNC_ERROR, dao.findById("local")?.syncState)
    }

    @Test
    fun restoreOverridesRemoteDeletionWithoutCreatingConflictCopy() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val metadata = DriveFileMetadata("drive-local", "local.md", "2", null)
        drive.addRemote(MarkdownNote("local", null, "body", 100, 100, 150), metadata)
        dao.upsert(
            note().copy(
                syncState = SyncState.PENDING_RESTORE,
                driveFileId = metadata.id,
                driveVersion = "1",
            ),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, result.conflicts)
        assertEquals(1, result.uploaded)
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        val downloaded = drive.downloadNoteFile(drive.listMarkdownFiles().single())
        assertEquals(null, MarkdownNoteCodec.decode(downloaded.content).deletedAtEpochMillis)
    }

    @Test
    fun permanentlyDeletesDriveAndLocalNote() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val metadata = DriveFileMetadata("drive-local", "local.md", "1", null)
        drive.addRemote(MarkdownNote("local", null, "body", 100, 200, 150), metadata)
        dao.upsert(
            note().copy(
                deletedAtEpochMillis = 150,
                syncState = SyncState.PENDING_PURGE,
                driveFileId = metadata.id,
                driveVersion = metadata.version,
            ),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(1, result.purged)
        assertEquals(null, dao.findById("local"))
        assertEquals(emptyList<DriveFileMetadata>(), drive.listMarkdownFiles())
    }

    @Test
    fun pinChangeDuringUploadRemainsPendingWithoutCreatingConflictCopy() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val metadata = DriveFileMetadata("drive-local", "local.md", "1", null)
        drive.addRemote(MarkdownNote("local", null, "body", 100, 100, null), metadata)
        dao.upsert(
            note().copy(
                isPinned = true,
                syncState = SyncState.PENDING_UPLOAD,
                driveFileId = metadata.id,
                driveVersion = metadata.version,
            ),
        )
        drive.onUpdate = {
            val current = requireNotNull(dao.findById("local"))
            dao.upsert(current.copy(isPinned = false, syncState = SyncState.PENDING_UPLOAD))
        }

        val first = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, first.conflicts)
        assertEquals(false, dao.findById("local")?.isPinned)
        assertEquals(SyncState.PENDING_UPLOAD, dao.findById("local")?.syncState)
        assertEquals("2", dao.findById("local")?.driveVersion)

        drive.onUpdate = null
        val second = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, second.conflicts)
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals(false, MarkdownNoteCodec.decode(
            drive.downloadNoteFile(drive.listMarkdownFiles().single()).content,
        ).isPinned)
    }

    @Test
    fun staleDriveVersionForPinOnlyChangeDoesNotCreateConflictCopy() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val remoteMetadata = DriveFileMetadata("drive-local", "local.md", "2", null)
        drive.addRemote(
            MarkdownNote("local", null, "body", 100, 100, null, isPinned = true),
            remoteMetadata,
        )
        dao.upsert(
            note().copy(
                isPinned = false,
                syncState = SyncState.PENDING_UPLOAD,
                driveFileId = remoteMetadata.id,
                driveVersion = "1",
            ),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, result.conflicts)
        assertEquals(1, result.uploaded)
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals(false, MarkdownNoteCodec.decode(
            drive.downloadNoteFile(drive.listMarkdownFiles().single()).content,
        ).isPinned)
    }

    @Test
    fun cancelledSyncDoesNotTurnPendingNoteIntoSyncError() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        dao.upsert(note())
        drive.cancelCreates = true

        try {
            DriveSyncEngine(dao, drive).synchronize()
        } catch (_: CancellationException) {
            // Expected when the screen that owns automatic sync is closed.
        }

        assertEquals(SyncState.LOCAL_ONLY, dao.findById("local")?.syncState)
        assertEquals(null, dao.findById("local")?.lastSyncError)
    }

    @Test
    fun tagRenameThatArrivesDuringPullIsUploadedInsteadOfReverted() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val remoteMetadata = DriveFileMetadata("drive-local", "local.md", "2", null)
        drive.addRemote(
            MarkdownNote("local", null, "body", 100, 100, null, tags = listOf("動画")),
            remoteMetadata,
        )
        dao.upsert(
            note().copy(
                tagsSerialized = "動画",
                syncState = SyncState.SYNCED,
                driveFileId = remoteMetadata.id,
                driveVersion = "1",
            ),
        )
        drive.onList = {
            val current = requireNotNull(dao.findById("local"))
            dao.upsert(current.copy(tagsSerialized = "動画編集", syncState = SyncState.PENDING_UPLOAD))
        }

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, result.conflicts)
        assertEquals(listOf("動画編集"), dao.findById("local")?.tags())
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals(listOf("動画編集"), MarkdownNoteCodec.decode(
            drive.downloadNoteFile(drive.listMarkdownFiles().single()).content,
        ).tags)
    }

    @Test
    fun newerRemoteMetadataWinsWithoutConflictCopy() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val remoteMetadata = DriveFileMetadata("drive-local", "local.md", "2", null)
        drive.addRemote(
            MarkdownNote(
                "local", null, "body", 100, 100, null,
                tags = listOf("Drive側"), metadataUpdatedAtEpochMillis = 300,
            ),
            remoteMetadata,
        )
        dao.upsert(
            note().copy(
                tagsSerialized = "端末側",
                metadataUpdatedAtEpochMillis = 200,
                syncState = SyncState.PENDING_UPLOAD,
                driveFileId = remoteMetadata.id,
                driveVersion = "1",
            ),
        )

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, result.conflicts)
        assertEquals(listOf("Drive側"), dao.findById("local")?.tags())
        assertEquals(300L, dao.findById("local")?.metadataUpdatedAtEpochMillis)
    }

    @Test
    fun tagDeletionDuringDownloadIsUploadedInsteadOfReverted() = runBlocking {
        val dao = SyncFakeDao()
        val drive = SyncFakeDrive()
        val metadata = DriveFileMetadata("drive-local", "local.md", "3", null)
        drive.addRemote(
            MarkdownNote(
                "local", null, "body", 100, 100, null,
                tags = listOf("動画"), metadataUpdatedAtEpochMillis = 200,
            ),
            metadata,
        )
        dao.upsert(
            note().copy(
                tagsSerialized = "動画",
                metadataUpdatedAtEpochMillis = 200,
                syncState = SyncState.SYNCED,
                driveFileId = metadata.id,
                driveVersion = "2",
            ),
        )
        drive.onDownload = {
            val current = requireNotNull(dao.findById("local"))
            dao.upsert(
                current.copy(
                    tagsSerialized = "",
                    metadataUpdatedAtEpochMillis = 300,
                    syncState = SyncState.PENDING_UPLOAD,
                ),
            )
        }

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(0, result.conflicts)
        assertEquals(emptyList<String>(), dao.findById("local")?.tags())
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals(emptyList<String>(), MarkdownNoteCodec.decode(
            drive.downloadNoteFile(drive.listMarkdownFiles().single()).content,
        ).tags)
    }

    private fun note() = NoteEntity(
        "local", null, "body", 100, 100, null, SyncState.LOCAL_ONLY, null, null, null,
    )
}

private class SyncFakeDrive : DriveDataSource {
    private val files = linkedMapOf<String, DriveDownloadedFile>()
    var failUpdates = false
    var cancelCreates = false
    var onUpdate: (suspend () -> Unit)? = null
    var onList: (suspend () -> Unit)? = null
    var onDownload: (suspend () -> Unit)? = null

    fun addRemote(note: MarkdownNote, metadata: DriveFileMetadata) {
        files[metadata.id] = DriveDownloadedFile(metadata, MarkdownNoteCodec.encode(note))
    }

    override fun listMarkdownFiles(): List<DriveFileMetadata> {
        runBlocking { onList?.invoke() }
        onList = null
        return files.values.map { it.metadata }
    }

    override fun createNoteFile(noteId: String, content: String): DriveFileMetadata {
        if (cancelCreates) throw CancellationException("screen changed")
        val metadata = DriveFileMetadata("drive-$noteId", "$noteId.md", "1", null)
        files[metadata.id] = DriveDownloadedFile(metadata, content)
        return metadata
    }

    override fun updateNoteFile(fileId: String, content: String): DriveFileMetadata {
        if (failUpdates) error("network")
        runBlocking { onUpdate?.invoke() }
        val old = files.getValue(fileId).metadata
        val metadata = old.copy(version = ((old.version?.toInt() ?: 0) + 1).toString())
        files[fileId] = DriveDownloadedFile(metadata, content)
        return metadata
    }

    override fun downloadNoteFile(metadata: DriveFileMetadata): DriveDownloadedFile {
        val downloaded = files.getValue(metadata.id)
        runBlocking { onDownload?.invoke() }
        onDownload = null
        return downloaded
    }
    override fun getFileMetadata(fileId: String) = files.getValue(fileId).metadata
    override fun deleteNoteFile(fileId: String) {
        files.remove(fileId)
    }
}

private class SyncFakeDao : NoteDao {
    private val notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    override fun observeTimeline(): Flow<List<NoteEntity>> = notes
    override fun observeSearch(query: String): Flow<List<NoteEntity>> = notes
    override fun observeTrash(): Flow<List<NoteEntity>> = notes.map { list ->
        list.filter { it.deletedAtEpochMillis != null && it.syncState != SyncState.PENDING_PURGE }
    }
    override fun observeTaggableNotes(): Flow<List<NoteEntity>> = notes.map { list ->
        list.filter { it.syncState != SyncState.PENDING_PURGE }
    }
    override fun observeById(id: String): Flow<NoteEntity?> = notes.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun findById(id: String) = notes.value.firstOrNull { it.id == id }
    override suspend fun findRecentMatchingNote(title: String?, body: String, createdAfter: Long) =
        notes.value.filter {
            it.deletedAtEpochMillis == null && it.title == title && it.body == body && it.createdAtEpochMillis >= createdAfter
        }.maxByOrNull { it.createdAtEpochMillis }
    override suspend fun findByDriveFileId(driveFileId: String) =
        notes.value.firstOrNull { it.driveFileId == driveFileId }
    override suspend fun findPendingSync() = notes.value.filter { it.syncState != SyncState.SYNCED }
    override suspend fun findDeleted() = notes.value.filter { it.deletedAtEpochMillis != null }
    override suspend fun findTaggableNotes() = notes.value.filter { it.syncState != SyncState.PENDING_PURGE }
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
