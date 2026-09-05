package com.digihori.solimemo.ui.notes

import android.content.Intent
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digihori.solimemo.R
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
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var draftBody by remember { mutableStateOf("") }

    LaunchedEffect(sharedText) {
        sharedText?.takeIf(String::isNotBlank)?.let {
            draftBody = it
            onSharedTextConsumed()
        }
    }

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
            LinkifiedText(
                text = legacyCompatibleBody(note),
                maxLines = 6,
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
private fun LinkifiedText(text: String, maxLines: Int = Int.MAX_VALUE) {
    val annotated = remember(text) {
        buildAnnotatedString {
            append(text)
            URL_PATTERN.findAll(text).forEach { match ->
                val url = match.value.trimEnd('.', ',', '。', '、', ')', '）', ']', '】')
                if (url.isNotEmpty()) {
                    addLink(
                        LinkAnnotation.Url(
                            url = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = Primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                        start = match.range.first,
                        end = match.range.first + url.length,
                    )
                }
            }
        }
    }
    Text(
        text = annotated,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TrashScreen(
    viewModel: NotesViewModel,
    onBack: () -> Unit,
) {
    val notes by viewModel.trashNotes.collectAsStateWithLifecycle()
    var purgeTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ゴミ箱") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Primary,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = { confirmEmpty = true }, enabled = notes.isNotEmpty()) {
                        Text("すべて削除", color = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "ゴミ箱は空です。",
                    modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notes, key = NoteEntity::id) { note ->
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LinkifiedText(legacyCompatibleBody(note), maxLines = 6)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(note.updatedAtEpochMillis)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = { viewModel.restore(note.id) }) { Text("復元") }
                                TextButton(onClick = { purgeTarget = note }) { Text("完全に削除") }
                            }
                        }
                    }
                }
            }
        }
    }

    purgeTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("完全に削除しますか？") },
            text = { Text("この操作は取り消せません。Drive上のメモも削除されます。") },
            confirmButton = {
                TextButton(onClick = { viewModel.purge(note.id); purgeTarget = null }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("キャンセル") } },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("ゴミ箱を空にしますか？") },
            text = { Text("ゴミ箱内のすべてのメモをDriveからも完全に削除します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = { viewModel.emptyTrash(); confirmEmpty = false }) { Text("すべて削除") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("キャンセル") } },
        )
    }
}

private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

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
    val context = LocalContext.current
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
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, body)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, context.getString(R.string.share_note)),
                            )
                        },
                        enabled = body.isNotBlank(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share_24),
                            contentDescription = context.getString(R.string.share_note),
                        )
                    }
                    IconButton(onClick = { showDeleteConfirmation = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_24),
                            contentDescription = context.getString(R.string.delete_note),
                        )
                    }
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
            text = { Text("メモはゴミ箱へ移動し、通常の一覧には表示されなくなります。") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(noteId, onBack) }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("キャンセル") }
            },
        )
    }
}
