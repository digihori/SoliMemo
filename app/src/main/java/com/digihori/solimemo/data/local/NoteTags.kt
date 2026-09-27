package com.digihori.solimemo.data.local

const val MAX_TAGS_PER_NOTE = 10
const val MAX_TAG_LENGTH = 30

fun NoteEntity.tags(): List<String> = decodeTags(tagsSerialized)

fun normalizeTags(values: Iterable<String>): List<String> = values
    .map(String::trim)
    .filter { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH && '\n' !in it && '\r' !in it }
    .distinct()
    .take(MAX_TAGS_PER_NOTE)

fun encodeTags(values: Iterable<String>): String = normalizeTags(values).joinToString("\n")

fun decodeTags(value: String): List<String> = normalizeTags(value.split('\n'))
