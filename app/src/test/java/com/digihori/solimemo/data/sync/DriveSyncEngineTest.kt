package com.digihori.solimemo.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState
import com.digihori.solimemo.data.remote.DriveDataSource
import com.digihori.solimemo.data.remote.DriveDownloadedFile
import com.digihori.solimemo.data.remote.DriveFileMetadata
import com.digihori.solimemo.data.remote.MarkdownNote
import com.digihori.solimemo.data.remote.MarkdownNoteCodec

class DriveSyncEngineTest {
    @Test
    fun uploadsLocalNoteAndMarksItSynced() = runBlocking {
        val dao = SyncFakeDao()
        dao.upsert(note())
        val drive = SyncFakeDrive()

        val result = DriveSyncEngine(dao, drive).synchronize()

        assertEquals(1, result.uploaded)
        assertEquals(SyncState.SYNCED, dao.findById("local")?.syncState)
        assertEquals("drive-local", dao.findById("local")?.driveFileId)
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

    private fun note() = NoteEntity(
        "local", null, "body", 100, 100, null, SyncState.LOCAL_ONLY, null, null, null,
    )
}

private class SyncFakeDrive : DriveDataSource {
    private val files = linkedMapOf<String, DriveDownloadedFile>()
    var failUpdates = false

    fun addRemote(note: MarkdownNote, metadata: DriveFileMetadata) {
        files[metadata.id] = DriveDownloadedFile(metadata, MarkdownNoteCodec.encode(note))
    }

    override fun listMarkdownFiles() = files.values.map { it.metadata }

    override fun createNoteFile(noteId: String, content: String): DriveFileMetadata {
        val metadata = DriveFileMetadata("drive-$noteId", "$noteId.md", "1", null)
        files[metadata.id] = DriveDownloadedFile(metadata, content)
        return metadata
    }

    override fun updateNoteFile(fileId: String, content: String): DriveFileMetadata {
        if (failUpdates) error("network")
        val old = files.getValue(fileId).metadata
        val metadata = old.copy(version = ((old.version?.toInt() ?: 0) + 1).toString())
        files[fileId] = DriveDownloadedFile(metadata, content)
        return metadata
    }

    override fun downloadNoteFile(metadata: DriveFileMetadata) = files.getValue(metadata.id)
    override fun getFileMetadata(fileId: String) = files.getValue(fileId).metadata
}

private class SyncFakeDao : NoteDao {
    private val notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    override fun observeTimeline(): Flow<List<NoteEntity>> = notes
    override fun observeSearch(query: String): Flow<List<NoteEntity>> = notes
    override fun observeById(id: String): Flow<NoteEntity?> = notes.map { list -> list.firstOrNull { it.id == id } }
    override suspend fun findById(id: String) = notes.value.firstOrNull { it.id == id }
    override suspend fun findByDriveFileId(driveFileId: String) =
        notes.value.firstOrNull { it.driveFileId == driveFileId }
    override suspend fun findPendingSync() = notes.value.filter { it.syncState != SyncState.SYNCED }
    override suspend fun upsert(note: NoteEntity) {
        notes.value = notes.value.filterNot { it.id == note.id } + note
    }
}
