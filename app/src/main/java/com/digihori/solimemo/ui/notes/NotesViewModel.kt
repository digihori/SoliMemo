package com.digihori.solimemo.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.local.tags
import com.digihori.solimemo.data.repository.NoteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val repository: NoteRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val selectedTag = MutableStateFlow<String?>(null)
    private var saveJob: Job? = null

    val notes = combine(
        query.flatMapLatest(repository::observeNotes),
        selectedTag,
    ) { notes, tag ->
        if (tag == null) notes else notes.filter { tag in it.tags() }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tagUsageCounts = repository.observeTaggableNotes()
        .map { notes ->
            notes.flatMap(NoteEntity::tags)
                .groupingBy { it }
                .eachCount()
                .toSortedMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val managedTags = tagUsageCounts
        .map { it.keys.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags = repository.observeNotes("")
        .map { notes -> notes.flatMap(NoteEntity::tags).distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trashNotes = repository.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setTagFilter(value: String?) {
        selectedTag.value = value
    }

    fun createNote(title: String, body: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            onComplete(repository.create(title, body) != null)
        }
    }

    fun observeNote(id: String): Flow<NoteEntity?> = repository.observeNote(id)

    fun scheduleSave(id: String, title: String, body: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            repository.update(id, title, body)
        }
    }

    fun flushSave(id: String, title: String, body: String) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { repository.update(id, title, body) }
    }

    fun delete(id: String, onDeleted: () -> Unit) {
        saveJob?.cancel()
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun purge(id: String) {
        viewModelScope.launch { repository.purge(id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repository.emptyTrash() }
    }

    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(id, pinned) }
    }

    fun setTags(id: String, tags: List<String>) {
        viewModelScope.launch { repository.setTags(id, tags) }
    }

    fun renameTag(oldTag: String, newTag: String) {
        viewModelScope.launch {
            repository.renameTag(oldTag, newTag)
            if (selectedTag.value == oldTag) selectedTag.value = newTag.trim()
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            repository.deleteTag(tag)
            if (selectedTag.value == tag) selectedTag.value = null
        }
    }

    class Factory(private val repository: NoteRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotesViewModel(repository) as T
    }
}
