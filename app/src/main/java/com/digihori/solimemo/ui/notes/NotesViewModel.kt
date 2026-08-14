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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.digihori.solimemo.data.local.NoteEntity
import com.digihori.solimemo.data.repository.NoteRepository

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(
    private val repository: NoteRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    private var saveJob: Job? = null

    val notes = query
        .flatMapLatest(repository::observeNotes)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    fun createNote(body: String, onCreated: () -> Unit = {}) {
        viewModelScope.launch {
            if (repository.create(body) != null) onCreated()
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

    class Factory(private val repository: NoteRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotesViewModel(repository) as T
    }
}
