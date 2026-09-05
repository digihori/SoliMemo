package com.digihori.solimemo.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class DriveRestClient(private val accessToken: String) : DriveDataSource {
    data class FileMetadata(
        val id: String,
        val name: String,
        val version: String?,
        val modifiedTime: String?,
    )

    data class DownloadedFile(
        val metadata: FileMetadata,
        val content: String,
    )

    override fun listMarkdownFiles(): List<DriveFileMetadata> {
        val query = "mimeType = '$MARKDOWN_MIME_TYPE' and trashed = false"
        val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val files = JSONObject(
            request(
                "GET",
                "$FILES_ENDPOINT?q=$encodedQuery&spaces=drive&pageSize=1000" +
                    "&fields=files(id,name,version,modifiedTime)",
            ),
        ).getJSONArray("files")
        return buildList {
            for (index in 0 until files.length()) add(parseDriveMetadata(files.getJSONObject(index).toString()))
        }
    }

    override fun createNoteFile(noteId: String, content: String): DriveFileMetadata {
        val rootFolderId = findOrCreateFolder("SoliMemo", "root")
        val notesFolderId = findOrCreateFolder("notes", rootFolderId)
        return createMarkdownFile("$noteId.md", notesFolderId, content).toDriveMetadata()
    }

    override fun updateNoteFile(fileId: String, content: String): DriveFileMetadata {
        updateFile(fileId, content)
        return getFileMetadata(fileId)
    }

    override fun downloadNoteFile(metadata: DriveFileMetadata): DriveDownloadedFile =
        DriveDownloadedFile(metadata, downloadFile(metadata.id))

    override fun getFileMetadata(fileId: String): DriveFileMetadata =
        getMetadata(fileId).toDriveMetadata()

    override fun deleteNoteFile(fileId: String) {
        request("DELETE", "$FILES_ENDPOINT/$fileId")
    }

    fun runProofOfConcept(onStep: (String) -> Unit): FileMetadata {
        onStep("1/5 SoliMemoフォルダを確認しています")
        val rootFolderId = findOrCreateFolder("SoliMemo", "root")
        onStep("2/5 notesフォルダを確認しています")
        val notesFolderId = findOrCreateFolder("notes", rootFolderId)

        val noteId = UUID.randomUUID().toString()
        val originalBody = markdown(noteId, "Androidから作成したPhase 1 PoCメモ")
        onStep("3/5 Markdownファイルを作成しています")
        val created = createMarkdownFile("$noteId.md", notesFolderId, originalBody)

        onStep("4/5 作成したファイルを読み戻しています")
        check(downloadFile(created.id) == originalBody) { "作成直後の内容が一致しません" }

        val updatedBody = markdown(noteId, "Androidから更新したPhase 1 PoCメモ")
        onStep("5/5 ファイルを更新して再読込しています")
        updateFile(created.id, updatedBody)
        check(downloadFile(created.id) == updatedBody) { "更新後の内容が一致しません" }
        return getMetadata(created.id)
    }

    fun downloadLatestMarkdown(): DownloadedFile {
        val query = "mimeType = '$MARKDOWN_MIME_TYPE' and trashed = false"
        val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val response = JSONObject(
            request(
                "GET",
                "$FILES_ENDPOINT?q=$encodedQuery&spaces=drive&orderBy=modifiedTime%20desc" +
                    "&pageSize=1&fields=files(id,name,version,modifiedTime)",
            ),
        )
        val files = response.getJSONArray("files")
        check(files.length() > 0) { "再取得できるMarkdownファイルがありません" }
        val metadata = parseMetadata(files.getJSONObject(0).toString())
        return DownloadedFile(metadata, downloadFile(metadata.id))
    }

    private fun findOrCreateFolder(name: String, parentId: String): String {
        val escapedName = name.replace("'", "\\'")
        val query = "name = '$escapedName' and mimeType = '$FOLDER_MIME_TYPE' " +
            "and '$parentId' in parents and trashed = false"
        val encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val files = JSONObject(
            request("GET", "$FILES_ENDPOINT?q=$encodedQuery&spaces=drive&fields=files(id,name)"),
        ).getJSONArray("files")
        if (files.length() > 0) return files.getJSONObject(0).getString("id")

        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME_TYPE)
            .put("parents", JSONArray().put(parentId))
        return JSONObject(
            request("POST", "$FILES_ENDPOINT?fields=id", "application/json", metadata.toString()),
        ).getString("id")
    }

    private fun createMarkdownFile(name: String, parentId: String, body: String): FileMetadata {
        val boundary = "solimemo-${UUID.randomUUID()}"
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", MARKDOWN_MIME_TYPE)
            .put("parents", JSONArray().put(parentId))
        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: $MARKDOWN_MIME_TYPE; charset=UTF-8\r\n\r\n")
            append(body).append("\r\n")
            append("--$boundary--\r\n")
        }
        return parseMetadata(
            request(
                "POST",
                "$UPLOAD_ENDPOINT?uploadType=multipart&fields=id,name,version,modifiedTime",
                "multipart/related; boundary=$boundary",
                multipartBody,
            ),
        )
    }

    private fun downloadFile(fileId: String): String =
        request("GET", "$FILES_ENDPOINT/$fileId?alt=media")

    private fun updateFile(fileId: String, body: String) {
        request(
            "PATCH",
            "$UPLOAD_ENDPOINT/$fileId?uploadType=media&fields=id,version,modifiedTime",
            "$MARKDOWN_MIME_TYPE; charset=UTF-8",
            body,
        )
    }

    private fun getMetadata(fileId: String): FileMetadata = parseMetadata(
        request("GET", "$FILES_ENDPOINT/$fileId?fields=id,name,version,modifiedTime"),
    )

    private fun parseMetadata(json: String): FileMetadata {
        val value = JSONObject(json)
        return FileMetadata(
            id = value.getString("id"),
            name = value.getString("name"),
            version = value.optString("version").takeIf(String::isNotEmpty),
            modifiedTime = value.optString("modifiedTime").takeIf(String::isNotEmpty),
        )
    }

    private fun parseDriveMetadata(json: String): DriveFileMetadata = parseMetadata(json).toDriveMetadata()

    private fun FileMetadata.toDriveMetadata() = DriveFileMetadata(id, name, version, modifiedTime)

    private fun request(
        method: String,
        url: String,
        contentType: String? = null,
        body: String? = null,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            if (body != null) doOutput = true
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IOException("Drive API HTTP $status: ${response.take(500)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun markdown(id: String, body: String): String = """
        ---
        schemaVersion: 1
        id: $id
        title: Phase 1 PoC
        createdAt: 2026-08-14T00:00:00.000Z
        updatedAt: 2026-08-14T00:00:00.000Z
        deletedAt: null
        ---

        $body
    """.trimIndent() + "\n"

    companion object {
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val MARKDOWN_MIME_TYPE = "text/markdown"
    }
}
