package com.digihori.solimemo.ui.sync

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.digihori.solimemo.SoliMemoApplication

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Composable
fun DriveSyncAction(application: SoliMemoApplication) {
    val activity = checkNotNull(LocalActivity.current)
    val client = remember(activity) { Identity.getAuthorizationClient(activity) }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun synchronize(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            running = false
            message = "アクセストークンを取得できませんでした。"
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    application.createSyncEngine(token).synchronize { progress ->
                        scope.launch { message = progress }
                    }
                }
            }.onSuccess { summary ->
                message = "同期完了\n送信: ${summary.uploaded}件\n取得: ${summary.downloaded}件\n" +
                    "競合コピー: ${summary.conflicts}件\nエラー: ${summary.errors}件"
            }.onFailure { error ->
                message = "同期に失敗しました。\n${error.message ?: error::class.java.simpleName}"
            }
            running = false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCatching { client.getAuthorizationResultFromIntent(result.data!!) }
                .onSuccess(::synchronize)
                .onFailure {
                    running = false
                    message = "Google認証に失敗しました。"
                }
        } else {
            running = false
            message = "同期をキャンセルしました。"
        }
    }

    fun authorize() {
        running = true
        message = "Google Driveへのアクセス権を確認しています。"
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        client.authorize(request)
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    synchronize(result)
                }
            }
            .addOnFailureListener {
                running = false
                message = "Google認証に失敗しました。"
            }
    }

    IconButton(onClick = ::authorize, enabled = !running) {
        if (running) CircularProgressIndicator(color = Color.White)
        else Text("↻", color = Color.White)
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { if (!running) message = null },
            title = { Text(if (running) "同期中" else "Google Drive同期") },
            text = { Text(text) },
            confirmButton = {
                if (!running) TextButton(onClick = { message = null }) { Text("閉じる") }
            },
        )
    }
}
