package com.digihori.solimemo

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import com.digihori.solimemo.ui.poc.DrivePocScreen
import com.digihori.solimemo.ui.notes.NoteEditorScreen
import com.digihori.solimemo.ui.notes.NotesViewModel
import com.digihori.solimemo.ui.notes.TimelineContent
import com.digihori.solimemo.ui.sync.DriveSyncAction
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoliMemoApp() }
    }
}

private enum class Screen {
    HOME,
    NOTE_EDITOR,
    DRIVE_POC,
    PRIVACY_POLICY,
    VERSION_INFORMATION,
}

private val SoliMemoPrimary = Color(0xFF3949AB)

@Composable
fun SoliMemoApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    val application = LocalContext.current.applicationContext as SoliMemoApplication
    val notesViewModel: NotesViewModel = viewModel(
        factory = NotesViewModel.Factory(application.noteRepository),
    )

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
                )
                Screen.NOTE_EDITOR -> selectedNoteId?.let { noteId ->
                    NoteEditorScreen(
                        noteId = noteId,
                        noteFlow = remember(noteId) { notesViewModel.observeNote(noteId) },
                        viewModel = notesViewModel,
                        onBack = { screen = Screen.HOME },
                    )
                }
                Screen.DRIVE_POC -> DetailScreen(
                    title = stringResource(R.string.phase1_menu),
                    onBack = { screen = Screen.HOME },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) { DrivePocScreen() }
                }
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
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SoliMemoPrimary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
                actions = {
                    val application = LocalContext.current.applicationContext as SoliMemoApplication
                    DriveSyncAction(application, onSyncStatusChange)
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("⋮", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.phase1_menu)) },
                                onClick = {
                                    menuExpanded = false
                                    onNavigate(Screen.DRIVE_POC)
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
                },
            )
        },
    ) { contentPadding ->
        TimelineContent(
            viewModel = viewModel,
            syncStatus = syncStatus,
            onOpenNote = onOpenNote,
            modifier = Modifier.padding(contentPadding),
        )
    }
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
