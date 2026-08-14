package com.digihori.solimemo.data.remote

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MarkdownNote(
    val id: String,
    val title: String?,
    val body: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
)

class MarkdownFormatException(message: String) : IllegalArgumentException(message)

object MarkdownNoteCodec {
    private const val SCHEMA_VERSION = 1
    private val keys = setOf("schemaVersion", "id", "title", "createdAt", "updatedAt", "deletedAt")

    fun encode(note: MarkdownNote): String = buildString {
        appendLine("---")
        appendLine("schemaVersion: $SCHEMA_VERSION")
        appendLine("id: ${note.id}")
        appendLine("title: ${note.title?.let(::quote) ?: "null"}")
        appendLine("createdAt: ${formatTime(note.createdAtEpochMillis)}")
        appendLine("updatedAt: ${formatTime(note.updatedAtEpochMillis)}")
        appendLine("deletedAt: ${note.deletedAtEpochMillis?.let(::formatTime) ?: "null"}")
        appendLine("---")
        appendLine()
        append(note.body.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n'))
        append('\n')
    }

    fun decode(markdown: String): MarkdownNote {
        val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.firstOrNull() != "---") throw MarkdownFormatException("front matterがありません")
        val closing = lines.indexOfFirstFrom(1) { it == "---" }
        if (closing < 0) throw MarkdownFormatException("front matterが閉じられていません")
        val values = mutableMapOf<String, String>()
        for (line in lines.subList(1, closing)) {
            val separator = line.indexOf(':')
            if (separator <= 0) throw MarkdownFormatException("front matterの行が不正です")
            val key = line.substring(0, separator).trim()
            if (key in keys) values[key] = line.substring(separator + 1).trim()
        }
        val schema = values["schemaVersion"]?.toIntOrNull()
        if (schema != SCHEMA_VERSION) throw MarkdownFormatException("未対応のschemaVersionです")
        val id = values.required("id")
        val bodyStart = if (lines.getOrNull(closing + 1).orEmpty().isEmpty()) closing + 2 else closing + 1
        return MarkdownNote(
            id = id,
            title = parseNullableString(values.required("title")),
            body = lines.drop(bodyStart).joinToString("\n").trimEnd('\n'),
            createdAtEpochMillis = parseTime(values.required("createdAt")),
            updatedAtEpochMillis = parseTime(values.required("updatedAt")),
            deletedAtEpochMillis = values.required("deletedAt").takeUnless { it == "null" }?.let(::parseTime),
        )
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    private fun parseNullableString(value: String): String? = when {
        value == "null" -> null
        value.startsWith('"') -> unquote(value)
        else -> value
    }

    private fun unquote(value: String): String {
        if (value.length < 2 || value.last() != '"') throw MarkdownFormatException("titleの引用形式が不正です")
        return buildString {
            var index = 1
            while (index < value.lastIndex) {
                val character = value[index++]
                if (character != '\\') {
                    append(character)
                    continue
                }
                if (index >= value.lastIndex) throw MarkdownFormatException("titleのエスケープが不正です")
                when (val escaped = value[index++]) {
                    '"', '\\', '/' -> append(escaped)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u' -> {
                        if (index + 4 > value.lastIndex) throw MarkdownFormatException("titleのUnicode escapeが不正です")
                        append(value.substring(index, index + 4).toIntOrNull(16)?.toChar()
                            ?: throw MarkdownFormatException("titleのUnicode escapeが不正です"))
                        index += 4
                    }
                    else -> throw MarkdownFormatException("titleのエスケープが不正です")
                }
            }
        }
    }

    private fun formatTime(value: Long): String = formatter().format(Date(value))

    private fun parseTime(value: String): Long = runCatching { formatter().parse(value)?.time }
        .getOrNull() ?: throw MarkdownFormatException("日時形式が不正です")

    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun Map<String, String>.required(key: String): String =
        this[key] ?: throw MarkdownFormatException("$key がありません")

    private inline fun <T> List<T>.indexOfFirstFrom(start: Int, predicate: (T) -> Boolean): Int {
        for (index in start until size) if (predicate(this[index])) return index
        return -1
    }
}
