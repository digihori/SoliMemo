package com.digihori.solimemo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import com.digihori.solimemo.ui.notes.NoteEditorScreen
import com.digihori.solimemo.ui.notes.NotesViewModel
import com.digihori.solimemo.ui.notes.TimelineContent
import com.digihori.solimemo.ui.notes.TrashScreen
import com.digihori.solimemo.ui.sync.DriveSyncAction
import com.digihori.solimemo.data.local.MAX_TAG_LENGTH
import java.net.URL

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = intent.sharedMemoText()
        setContent {
            SoliMemoApp(
                sharedText = sharedText,
                onSharedTextConsumed = { sharedText = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText = intent.sharedMemoText()
    }
}

private fun Intent.sharedMemoText(): String? {
    if (action != Intent.ACTION_SEND || type?.startsWith("text/") != true) return null
    val subject = getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    return listOf(subject, text).filter(String::isNotEmpty).distinct().joinToString("\n\n").ifEmpty { null }
}

private enum class Screen {
    HOME,
    NOTE_EDITOR,
    TRASH,
    WEB_INFORMATION,
    PRIVACY_POLICY,
    VERSION_INFORMATION,
}

private val SoliMemoPrimary = Color(0xFF3949AB)
private const val SEARCH_HISTORY_PREFERENCES = "search_history"
private const val SEARCH_HISTORY_KEY = "queries"
private const val SEARCH_HISTORY_LIMIT = 10

@Composable
fun SoliMemoApp(
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    val application = LocalContext.current.applicationContext as SoliMemoApplication
    val notesViewModel: NotesViewModel = viewModel(
        factory = NotesViewModel.Factory(application.noteRepository),
    )

    LaunchedEffect(sharedText) {
        if (sharedText != null) screen = Screen.HOME
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    viewModel = notesViewModel,
                    syncStatus = syncStatus,
                    onSyncStatusChange = { syncStatus = it },
                    onOpenNote = { noteId ->
                        selectedNoteId = noteId
                        screen = Screen.NOTE_EDITOR
                    },
                    onNavigate = { screen = it },
                    returnToNoteId = selectedNoteId,
                    sharedText = sharedText,
                    onSharedTextConsumed = onSharedTextConsumed,
                )
                Screen.NOTE_EDITOR -> selectedNoteId?.let { noteId ->
                    NoteEditorScreen(
                        noteId = noteId,
                        noteFlow = remember(noteId) { notesViewModel.observeNote(noteId) },
                        viewModel = notesViewModel,
                        onBack = { screen = Screen.HOME },
                    )
                }
                Screen.TRASH -> TrashScreen(
                    viewModel = notesViewModel,
                    onBack = { screen = Screen.HOME },
                )
                Screen.WEB_INFORMATION -> DetailScreen(
                    title = stringResource(R.string.web_information),
                    onBack = { screen = Screen.HOME },
                ) { WebInformationContent() }
                Screen.PRIVACY_POLICY -> DetailScreen(
                    title = stringResource(R.string.privacy_policy),
                    onBack = { screen = Screen.HOME },
                ) { PrivacyPolicyContent() }
                Screen.VERSION_INFORMATION -> DetailScreen(
                    title = stringResource(R.string.version_information),
                    onBack = { screen = Screen.HOME },
                ) { VersionInformationContent() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    viewModel: NotesViewModel,
    syncStatus: String?,
    onSyncStatusChange: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
    returnToNoteId: String?,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var showTagFilter by remember { mutableStateOf(false) }
    var showTagManagement by remember { mutableStateOf(false) }
    var renameTag by remember { mutableStateOf<String?>(null) }
    var renameTagValue by remember { mutableStateOf("") }
    var deleteTag by remember { mutableStateOf<String?>(null) }
    var searchHistory by remember { mutableStateOf(loadSearchHistory(context)) }
    val searchFocusRequester = remember { FocusRequester() }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val managedTags by viewModel.managedTags.collectAsStateWithLifecycle()
    val tagUsageCounts by viewModel.tagUsageCounts.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()

    LaunchedEffect(searchMode) {
        if (searchMode) searchFocusRequester.requestFocus()
    }

    fun recordSearchQuery() {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        searchHistory = (listOf(normalized) + searchHistory.filterNot { it == normalized })
            .take(SEARCH_HISTORY_LIMIT)
        saveSearchHistory(context, searchHistory)
    }

    fun closeSearch() {
        recordSearchQuery()
        searchMode = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searchMode, onBack = ::closeSearch)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchMode) {
                        TextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text("メモを検索", color = Color.White.copy(alpha = .75f)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { recordSearchQuery() }),
                            colors = TextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.White,
                                unfocusedIndicatorColor = Color.White.copy(alpha = .6f),
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SoliMemoPrimary,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                navigationIcon = {
                    if (searchMode) {
                        IconButton(onClick = ::closeSearch) {
                            Text("←", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                },
                actions = {
                    if (searchMode) {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Text("×", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    } else {
                        val application = LocalContext.current.applicationContext as SoliMemoApplication
                        DriveSyncAction(application, onSyncStatusChange)
                        IconButton(onClick = { searchMode = true }) {
                            Text("⌕", style = MaterialTheme.typography.headlineSmall)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Text("⋮", style = MaterialTheme.typography.headlineSmall)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("タグ${selectedTag?.let { ": $it" }.orEmpty()}") },
                                    onClick = {
                                        menuExpanded = false
                                        showTagFilter = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.trash)) },
                                    onClick = {
                                        menuExpanded = false
                                        onNavigate(Screen.TRASH)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.web_information)) },
                                    onClick = {
                                        menuExpanded = false
                                        onNavigate(Screen.WEB_INFORMATION)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.privacy_policy)) },
                                    onClick = {
                                        menuExpanded = false
                                        onNavigate(Screen.PRIVACY_POLICY)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.version_information)) },
                                    onClick = {
                                        menuExpanded = false
                                        onNavigate(Screen.VERSION_INFORMATION)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding)) {
            TimelineContent(
                viewModel = viewModel,
                syncStatus = syncStatus,
                composerVisible = !searchMode,
                sharedText = sharedText,
                onSharedTextConsumed = onSharedTextConsumed,
                onOpenNote = onOpenNote,
                returnToNoteId = returnToNoteId,
            )
            if (searchMode && query.isBlank() && searchHistory.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("検索履歴", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                searchHistory = emptyList()
                                saveSearchHistory(context, searchHistory)
                            }) { Text("すべて削除") }
                        }
                        searchHistory.forEach { pastQuery ->
                            TextButton(
                                onClick = { viewModel.setQuery(pastQuery) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = pastQuery,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTagFilter) {
        AlertDialog(
            onDismissRequest = { showTagFilter = false },
            title = { Text("タグで絞り込み") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (allTags.isEmpty()) {
                        Text("タグがまだありません。")
                    } else {
                        allTags.forEach { tag ->
                            TextButton(
                                onClick = {
                                    viewModel.setTagFilter(tag)
                                    showTagFilter = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("#$tag") }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (managedTags.isNotEmpty()) {
                        TextButton(onClick = {
                            showTagFilter = false
                            showTagManagement = true
                        }) { Text("タグを管理") }
                    }
                    if (selectedTag != null) {
                        TextButton(onClick = { viewModel.setTagFilter(null); showTagFilter = false }) {
                            Text("絞り込みを解除")
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showTagFilter = false }) { Text("閉じる") } },
        )
    }

    if (showTagManagement) {
        AlertDialog(
            onDismissRequest = { showTagManagement = false },
            title = { Text("タグを管理") },
            text = {
                Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (managedTags.isEmpty()) {
                        Text("タグがまだありません。")
                    } else {
                        managedTags.forEach { tag ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text("#$tag（${tagUsageCounts[tag] ?: 0}件）")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        renameTag = tag
                                        renameTagValue = tag
                                    }) { Text("名前変更") }
                                    TextButton(onClick = { deleteTag = tag }) { Text("削除") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTagManagement = false }) { Text("閉じる") } },
        )
    }

    renameTag?.let { oldTag ->
        val normalized = renameTagValue.trim()
        val canRename = normalized.isNotEmpty() &&
            normalized.length <= MAX_TAG_LENGTH &&
            '\n' !in normalized && '\r' !in normalized &&
            normalized != oldTag
        AlertDialog(
            onDismissRequest = { renameTag = null },
            title = { Text("タグ名を変更") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("「#$oldTag」を使用している${tagUsageCounts[oldTag] ?: 0}件のメモを変更します。")
                    OutlinedTextField(
                        value = renameTagValue,
                        onValueChange = { if (it.length <= MAX_TAG_LENGTH) renameTagValue = it },
                        label = { Text("タグ名") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canRename,
                    onClick = {
                        viewModel.renameTag(oldTag, normalized)
                        renameTag = null
                    },
                ) { Text("変更") }
            },
            dismissButton = { TextButton(onClick = { renameTag = null }) { Text("キャンセル") } },
        )
    }

    deleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteTag = null },
            title = { Text("タグを削除") },
            text = { Text("「#$tag」を${tagUsageCounts[tag] ?: 0}件のメモから削除します。メモ本体は削除されません。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tag)
                    deleteTag = null
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteTag = null }) { Text("キャンセル") } },
        )
    }
}

private fun loadSearchHistory(context: Context): List<String> =
    context.getSharedPreferences(SEARCH_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        .getString(SEARCH_HISTORY_KEY, null)
        ?.split('\u001F')
        ?.filter(String::isNotBlank)
        ?.take(SEARCH_HISTORY_LIMIT)
        .orEmpty()

private fun saveSearchHistory(context: Context, queries: List<String>) {
    context.getSharedPreferences(SEARCH_HISTORY_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(SEARCH_HISTORY_KEY, queries.joinToString("\u001F"))
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SoliMemoPrimary,
                    navigationIconContentColor = Color.White,
                    titleContentColor = Color.White,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.headlineSmall)
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

@Composable
private fun WebInformationContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.web_information_heading),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.web_information_description),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        SelectionContainer {
            Text(
                text = stringResource(R.string.web_app_url),
                modifier = Modifier.padding(top = 20.dp),
                color = SoliMemoPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun PrivacyPolicyContent() {
    val policyUrl = stringResource(R.string.privacy_policy_url)
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var renderedHtml by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(policyUrl) {
        runCatching {
            withContext(Dispatchers.IO) {
                val markdown = URL(policyUrl).readText(Charsets.UTF_8)
                val document = Parser.builder().build().parse(markdown)
                val content = HtmlRenderer.builder()
                    .escapeHtml(true)
                    .sanitizeUrls(true)
                    .build()
                    .render(document)

                """
                <!doctype html>
                <html lang="ja">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body {
                            color: #202124;
                            font-family: sans-serif;
                            font-size: 16px;
                            line-height: 1.7;
                            margin: 0;
                            padding: 20px 20px 40px;
                            overflow-wrap: anywhere;
                        }
                        h1 { font-size: 1.6rem; margin-top: 0; }
                        h2 { font-size: 1.25rem; margin-top: 1.8rem; }
                        a { color: #3949AB; }
                        hr { border: 0; border-top: 1px solid #dadce0; }
                    </style>
                </head>
                <body>$content</body>
                </html>
                """.trimIndent()
            }
        }.onSuccess {
            renderedHtml = it
            loading = false
        }.onFailure {
            loadFailed = true
            loading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        renderedHtml?.let { html ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                    settings.javaScriptEnabled = false
                        loadDataWithBaseURL(policyUrl, html, "text/html", "UTF-8", null)
                    }
                },
                onRelease = WebView::destroy,
            )
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (loadFailed) {
            Text(
                text = "プライバシーポリシーを読み込めませんでした。\nネットワーク接続を確認してください。",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VersionInformationContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(
            text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.copyright),
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SoliMemoPreview() {
    Text("SoliMemo")
}
