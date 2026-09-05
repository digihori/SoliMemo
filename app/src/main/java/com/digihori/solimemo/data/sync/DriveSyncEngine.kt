package com.digihori.solimemo.data.sync

import java.util.UUID
import com.digihori.solimemo.data.local.NoteDao
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.SyncState
import com.digihori.solimemo.data.remote.DriveDataSource
import com.digihori.solimemo.data.remote.DriveDownloadedFile
import com.digihori.solimemo.data.remote.MarkdownNote
import com.digihori.solimemo.data.remote.MarkdownNoteCodec

data class SyncSummary(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val errors: Int,
    val purged: Int,
)

class DriveSyncEngine(
    private val noteDao: NoteDao,
    private val drive: DriveDataSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun synchronize(onProgress: (String) -> Unit = {}): SyncSummary {
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var errors = 0
        var purged = 0

        val pending = noteDao.findPendingSync()
        pending.forEachIndexed { index, note ->
            onProgress("Driveへ送信中 ${index + 1}/${pending.size}")
            runCatching {
                if (note.syncState == SyncState.PENDING_PURGE) {
                    purge(note)
                    true
                } else {
                    upload(note)
                }
            }
                .onSuccess { changed ->
                    if (note.syncState == SyncState.PENDING_PURGE && changed) purged++
                    else if (changed) uploaded++
                }
                .onFailure {
                    errors++
                    noteDao.upsert(
                        note.copy(
                            syncState = when (note.syncState) {
                                SyncState.PENDING_PURGE -> SyncState.PENDING_PURGE
                                SyncState.PENDING_RESTORE -> SyncState.PENDING_RESTORE
                                else -> SyncState.SYNC_ERROR
                            },
                            lastSyncError = if (note.syncState == SyncState.PENDING_PURGE) {
                                "purge_failed"
                            } else {
                                "upload_failed"
                            },
                        ),
                    )
                }
        }

        val files = drive.listMarkdownFiles()
        files.forEachIndexed { index, metadata ->
            onProgress("Driveから確認中 ${index + 1}/${files.size}")
            val existingByFile = noteDao.findByDriveFileId(metadata.id)
            if (existingByFile?.syncState == SyncState.PENDING_RESTORE) {
                return@forEachIndexed
            }
            if (
                existingByFile != null &&
                existingByFile.driveVersion == metadata.version &&
                existingByFile.syncState == SyncState.SYNCED
            ) {
                return@forEachIndexed
            }
            if (
                existingByFile != null &&
                existingByFile.hasLocalChanges() &&
                existingByFile.driveVersion == metadata.version
            ) {
                return@forEachIndexed
            }
            runCatching { drive.downloadNoteFile(metadata) }
                .mapCatching { file -> file to MarkdownNoteCodec.decode(file.content) }
                .onSuccess { (file, remote) ->
                    val local = noteDao.findById(remote.id) ?: existingByFile
                    if (local != null && local.hasLocalChanges() && local.driveVersion != file.metadata.version) {
                        preserveConflictCopy(local)
                        conflicts++
                    }
                    noteDao.upsert(remote.toEntity(file))
                    downloaded++
                }
                .onFailure {
                    errors++
                    existingByFile?.let { note ->
                        noteDao.upsert(note.copy(syncState = SyncState.SYNC_ERROR, lastSyncError = "download_failed"))
                    }
                }
        }
        return SyncSummary(uploaded, downloaded, conflicts, errors, purged)
    }

    private suspend fun purge(note: NoteEntity) {
        note.driveFileId?.let(drive::deleteNoteFile)
        noteDao.deleteById(note.id)
    }

    private suspend fun upload(note: NoteEntity): Boolean {
        val content = MarkdownNoteCodec.encode(note.toMarkdownNote())
        val metadata = if (note.driveFileId == null) {
            drive.createNoteFile(note.id, content)
        } else {
            val current = drive.getFileMetadata(note.driveFileId)
            if (
                note.syncState != SyncState.PENDING_RESTORE &&
                note.driveVersion != null &&
                current.version != note.driveVersion
            ) {
                return false
            }
            drive.updateNoteFile(note.driveFileId, content)
        }
        noteDao.upsert(
            note.copy(
                driveFileId = metadata.id,
                driveVersion = metadata.version,
                syncState = SyncState.SYNCED,
                lastSyncError = null,
            ),
        )
        return true
    }

    private suspend fun preserveConflictCopy(note: NoteEntity) {
        val copyId = newId()
        noteDao.upsert(
            note.copy(
                id = copyId,
                title = note.title?.let { "$it（競合コピー）" } ?: "競合コピー",
                driveFileId = null,
                driveVersion = null,
                syncState = SyncState.LOCAL_ONLY,
                lastSyncError = null,
            ),
        )
    }

    private fun NoteEntity.hasLocalChanges() = syncState != SyncState.SYNCED

    private fun NoteEntity.toMarkdownNote() = MarkdownNote(
        id = id,
        title = title,
        body = body,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
    )

    private fun MarkdownNote.toEntity(file: DriveDownloadedFile) = NoteEntity(
        id = id,
        title = title,
        body = body,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
        syncState = SyncState.SYNCED,
        driveFileId = file.metadata.id,
        driveVersion = file.metadata.version,
        lastSyncError = null,
    )
}
