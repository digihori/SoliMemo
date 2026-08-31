package com.digihori.solimemo.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digihori.solimemo.data.local.NoteEntity
import kotlinx.coroutines.flow.Flow
import java.text.DateFormat
import java.util.Date

private val Primary = Color(0xFF3949AB)

@Composable
fun TimelineContent(
    viewModel: NotesViewModel,
    syncStatus: String?,
    composerVisible: Boolean,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var draftBody by remember { mutableStateOf("") }

    LaunchedEffect(notes.size, query, imeBottom) {
        if (notes.isNotEmpty()) listState.scrollToItem(notes.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (notes.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (query.isBlank()) {
                                "まだメモがありません。下の入力欄から最初のメモを投稿できます。"
                            } else {
                                "一致するメモがありません。"
                            },
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

        if (composerVisible) {
            Surface(shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    syncStatus
                        ?.takeIf(::isSyncProblem)
                        ?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = draftBody,
                            onValueChange = { draftBody = it },
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minHeight = 56.dp, maxHeight = 132.dp),
                            placeholder = { Text("メモを入力") },
                            minLines = 1,
                            maxLines = 4,
                        )
                        Button(
                            onClick = {
                                viewModel.createNote("", draftBody) {
                                    draftBody = ""
                                }
                            },
                            modifier = Modifier.sizeIn(minHeight = 56.dp),
                            enabled = draftBody.isNotBlank(),
                        ) { Text("投稿") }
                    }
                }
            }
        }
    }
}

private fun isSyncProblem(status: String): Boolean =
    status.contains("失敗") ||
    status.contains("必要") ||
    status.contains("できません") ||
    status.contains("完了しません") ||
    status.contains("キャンセル")

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
            Text(
                legacyCompatibleBody(note),
                maxLines = 6,
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

private fun legacyCompatibleBody(note: NoteEntity): String = buildString {
    note.title?.takeIf(String::isNotBlank)?.let {
        append(it)
        if (note.body.isNotBlank()) append("\n\n")
    }
    append(note.body)
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
    var body by remember(noteId) { mutableStateOf("") }
    var initialized by remember(noteId) { mutableStateOf(false) }
    var dirty by remember(noteId) { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(note?.id) {
        if (!initialized) {
            note?.let {
                body = legacyCompatibleBody(it)
                initialized = true
            }
        }
    }

    fun saveAndBack() {
        if (initialized && dirty) viewModel.flushSave(noteId, "", body)
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
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    dirty = true
                    viewModel.scheduleSave(noteId, "", body)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("メモ") },
            )
            HorizontalDivider()
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
