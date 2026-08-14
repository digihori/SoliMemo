package com.digihori.solimemo.data.remote

data class DriveFileMetadata(
    val id: String,
    val name: String,
    val version: String?,
    val modifiedTime: String?,
)

data class DriveDownloadedFile(
    val metadata: DriveFileMetadata,
    val content: String,
)

interface DriveDataSource {
    fun listMarkdownFiles(): List<DriveFileMetadata>
    fun createNoteFile(noteId: String, content: String): DriveFileMetadata
    fun updateNoteFile(fileId: String, content: String): DriveFileMetadata
    fun downloadNoteFile(metadata: DriveFileMetadata): DriveDownloadedFile
    fun getFileMetadata(fileId: String): DriveFileMetadata
}
