package com.digihori.solimemo.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import com.digihori.solimemo.data.local.NoteEntity
import java.text.DateFormat
import java.util.Date

private val Primary = Color(0xFF3949AB)

@Composable
fun TimelineContent(
    viewModel: NotesViewModel,
    syncStatus: String?,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "composer") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                syncStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("タイトル（任意）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draftBody,
                    onValueChange = { draftBody = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("メモの入力") },
                    minLines = 2,
                    maxLines = 5,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            viewModel.createNote(draftTitle, draftBody) {
                                draftTitle = ""
                                draftBody = ""
                            }
                        },
                        enabled = draftTitle.isNotBlank() || draftBody.isNotBlank(),
                    ) { Text("投稿") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("メモを検索") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) { Text("×") }
                        }
                    },
                )
            }
        }

        if (notes.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = if (query.isBlank()) "まだメモがありません。" else "一致するメモがありません。",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(notes, key = NoteEntity::id) { note ->
                NoteCard(note = note, onClick = { onOpenNote(note.id) })
            }
        }
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            note.title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                note.body,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(note.updatedAtEpochMillis)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NoteEditorScreen(
    noteId: String,
    noteFlow: Flow<NoteEntity?>,
    viewModel: NotesViewModel,
    onBack: () -> Unit,
) {
    val note by noteFlow.collectAsStateWithLifecycle(initialValue = null)
    var title by remember(noteId) { mutableStateOf("") }
    var body by remember(noteId) { mutableStateOf("") }
    var initialized by remember(noteId) { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(note?.id) {
        if (!initialized) {
            note?.let {
                title = it.title.orEmpty()
                body = it.body
                initialized = true
            }
        }
    }

    fun saveAndBack() {
        if (initialized) viewModel.flushSave(noteId, title, body)
        onBack()
    }
    BackHandler(onBack = ::saveAndBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("メモを編集") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = ::saveAndBack) { Text("←") }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirmation = true }) { Text("削除") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    viewModel.scheduleSave(noteId, title, body)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("タイトル（任意）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    viewModel.scheduleSave(noteId, title, body)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("本文") },
            )
            Text(
                "入力内容は自動保存されます",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("メモを削除しますか？") },
            text = { Text("メモは論理削除され、通常の一覧には表示されなくなります。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(noteId, onBack) }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("キャンセル") }
            },
        )
    }
}
