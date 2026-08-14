package com.digihori.solimemo.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownNoteCodecTest {
    @Test
    fun roundTripPreservesJapaneseEmojiAndEscapedTitle() {
        val note = MarkdownNote(
            id = "note-id",
            title = "釣り: \"予定\" 🎣",
            body = "日本語の本文\n二行目 🚢\n",
            createdAtEpochMillis = 1_755_216_000_000L,
            updatedAtEpochMillis = 1_755_216_123_000L,
            deletedAtEpochMillis = null,
        )

        val decoded = MarkdownNoteCodec.decode(MarkdownNoteCodec.encode(note))

        assertEquals(note.copy(body = note.body.trimEnd('\n')), decoded)
        assertNull(decoded.deletedAtEpochMillis)
    }

    @Test
    fun parserAcceptsDifferentKeyOrderAndCrLf() {
        val markdown = """
            ---
            id: abc
            updatedAt: 2026-08-14T00:00:01.000Z
            schemaVersion: 1
            deletedAt: null
            title: null
            createdAt: 2026-08-14T00:00:00.000Z
            ---

            body
        """.trimIndent().replace("\n", "\r\n")

        val decoded = MarkdownNoteCodec.decode(markdown)

        assertEquals("abc", decoded.id)
        assertEquals("body", decoded.body)
    }
}
