package com.digihori.solimemo.ui.poc

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.digihori.solimemo.R
import com.digihori.solimemo.data.remote.DriveRestClient

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

private enum class DriveAction {
    RUN_POC,
    RELOAD_LATEST,
}

@Composable
fun DrivePocScreen(modifier: Modifier = Modifier) {
    val activity = checkNotNull(LocalActivity.current) { "Drive PoC requires an Activity" }
    val authorizationClient = remember(activity) { Identity.getAuthorizationClient(activity) }
    val coroutineScope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(activity.getString(R.string.phase1_idle)) }
    var requestedAction by remember { mutableStateOf(DriveAction.RUN_POC) }
    var downloadedContent by remember { mutableStateOf<String?>(null) }

    fun runAuthorizedAction(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            running = false
            status = "失敗: アクセストークンを取得できませんでした"
            return
        }
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = DriveRestClient(token)
                    when (requestedAction) {
                        DriveAction.RUN_POC -> client.runProofOfConcept { step ->
                            coroutineScope.launch { status = step }
                        }
                        DriveAction.RELOAD_LATEST -> client.downloadLatestMarkdown()
                    }
                }
            }.onSuccess { resultValue ->
                when (resultValue) {
                    is DriveRestClient.FileMetadata -> {
                        downloadedContent = null
                        status = buildString {
                            appendLine("成功: Driveへの作成・読込・更新を確認しました")
                            appendLine("file: ${resultValue.name}")
                            appendLine("id: ${resultValue.id}")
                            append("version: ${resultValue.version ?: "不明"}")
                        }
                    }
                    is DriveRestClient.DownloadedFile -> {
                        downloadedContent = resultValue.content
                        status = buildString {
                            appendLine("再取得成功")
                            appendLine("file: ${resultValue.metadata.name}")
                            appendLine("version: ${resultValue.metadata.version ?: "不明"}")
                            append("modified: ${resultValue.metadata.modifiedTime ?: "不明"}")
                        }
                    }
                }
            }.onFailure { error ->
                status = "失敗: ${error.message ?: error::class.java.simpleName}"
            }
            running = false
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            running = false
            status = "キャンセルされました"
        } else {
            runCatching {
                authorizationClient.getAuthorizationResultFromIntent(result.data!!)
            }.onSuccess(::runAuthorizedAction).onFailure { error ->
                running = false
                status = "認証失敗: ${error.message ?: error::class.java.simpleName}"
            }
        }
    }

    fun authorize(action: DriveAction) {
        requestedAction = action
        running = true
        downloadedContent = null
        status = if (action == DriveAction.RUN_POC) {
            "Google Driveへのアクセス権を確認しています"
        } else {
            "Driveから最新のMarkdownを検索しています"
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        running = false
                        status = "認証失敗: 認証画面を開始できません"
                    } else {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                        )
                    }
                } else {
                    runAuthorizedAction(result)
                }
            }
            .addOnFailureListener { error ->
                running = false
                status = "認証失敗: ${error.message ?: error::class.java.simpleName}"
            }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.phase1_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.phase1_description))
            Button(onClick = { authorize(DriveAction.RUN_POC) }, enabled = !running) {
                Text(stringResource(R.string.phase1_run))
            }
            Button(onClick = { authorize(DriveAction.RELOAD_LATEST) }, enabled = !running) {
                Text(stringResource(R.string.phase1_reload))
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            downloadedContent?.let { content ->
                Text("取得した本文", style = MaterialTheme.typography.titleMedium)
                Text(content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
