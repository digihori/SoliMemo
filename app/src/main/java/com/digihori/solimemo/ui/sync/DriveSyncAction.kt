package com.digihori.solimemo.ui.sync

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import com.digihori.solimemo.SoliMemoApplication

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

@OptIn(FlowPreview::class)
@Composable
fun DriveSyncAction(
    application: SoliMemoApplication,
    onStatusChange: (String) -> Unit,
) {
    val activity = checkNotNull(LocalActivity.current)
    val client = remember(activity) { Identity.getAuthorizationClient(activity) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var autoSyncPending by remember { mutableStateOf(false) }

    fun synchronize(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            running = false
            onStatusChange("Driveの認証情報を取得できませんでした")
            return
        }
        onStatusChange("Drive同期中…")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    application.createSyncEngine(token).synchronize { progress ->
                        scope.launch { onStatusChange(progress) }
                    }
                }
            }.onSuccess { summary ->
                val details = buildList {
                    if (summary.uploaded > 0) add("送信 ${summary.uploaded}件")
                    if (summary.downloaded > 0) add("取得 ${summary.downloaded}件")
                    if (summary.conflicts > 0) add("競合 ${summary.conflicts}件")
                    if (summary.errors > 0) add("エラー ${summary.errors}件")
                }
                onStatusChange(
                    if (details.isEmpty()) "Drive同期済み"
                    else "Drive同期完了（${details.joinToString("・")}）",
                )
            }.onFailure { error ->
                onStatusChange("Drive同期失敗: ${error.message ?: error::class.java.simpleName}")
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
                    onStatusChange("Google Driveの認証に失敗しました")
                }
        } else {
            running = false
            onStatusChange("Google Driveの同期をキャンセルしました")
        }
    }

    fun authorize(interactive: Boolean) {
        if (running) {
            if (!interactive) autoSyncPending = true
            return
        }
        running = true
        if (interactive) onStatusChange("Google Driveへのアクセス権を確認中…")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        client.authorize(request)
            .addOnSuccessListener { result ->
                val pendingIntent = result.pendingIntent
                if (result.hasResolution() && pendingIntent != null) {
                    if (interactive) {
                        launcher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } else {
                        running = false
                        onStatusChange("Drive再接続が必要です（↻を押してください）")
                    }
                } else {
                    synchronize(result)
                }
            }
            .addOnFailureListener {
                running = false
                onStatusChange("Google Driveの認証に失敗しました")
            }
    }

    LaunchedEffect(application.noteRepository) {
        application.noteRepository.localChanges
            .debounce(2_000)
            .collect { authorize(interactive = false) }
    }

    LaunchedEffect(Unit) {
        val shouldSync = application.consumeInitialSyncRequest() ||
            withContext(Dispatchers.IO) { application.noteRepository.hasPendingChanges() }
        if (shouldSync) authorize(interactive = false)
    }

    LaunchedEffect(running, autoSyncPending) {
        if (!running && autoSyncPending) {
            autoSyncPending = false
            authorize(interactive = false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        var hasReachedResume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (hasReachedResume) authorize(interactive = false)
                }
                Lifecycle.Event.ON_RESUME -> hasReachedResume = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    IconButton(onClick = { authorize(interactive = true) }, enabled = !running) {
        if (running) CircularProgressIndicator(color = Color.White)
        else Text("↻", color = Color.White)
    }
}
