package tk.horiuchi.solimemo

import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SoliMemoApp() }
    }
}

private enum class Screen {
    HOME,
    PRIVACY_POLICY,
    VERSION_INFORMATION,
}

private val SoliMemoPrimary = Color(0xFF3949AB)

@Composable
fun SoliMemoApp() {
    var screen by remember { mutableStateOf(Screen.HOME) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(onNavigate = { screen = it })
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
private fun HomeScreen(onNavigate: (Screen) -> Unit) {
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
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text("⋮", style = MaterialTheme.typography.headlineSmall)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SoliMemo", style = MaterialTheme.typography.headlineLarge)
            Text("自分だけにつぶやくように、思いつきを残す。")
        }
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
    val context = LocalContext.current
    val policyUrl = stringResource(R.string.privacy_policy_url)
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                loadFailed = true
                            }
                        }
                    }
                    loadUrl(policyUrl)
                }
            },
        )

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
    SoliMemoApp()
}
