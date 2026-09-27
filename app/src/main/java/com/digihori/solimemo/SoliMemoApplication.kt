package com.digihori.solimemo

import android.app.Application
import androidx.room.Room
import com.digihori.solimemo.data.local.SoliMemoDatabase
import com.digihori.solimemo.data.repository.NoteRepository
import com.digihori.solimemo.data.remote.DriveRestClient
import com.digihori.solimemo.data.sync.DriveSyncEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.digihori.solimemo.data.sync.SyncSummary

class SoliMemoApplication : Application() {
    private val initialSyncPending = AtomicBoolean(true)
    private val syncMutex = Mutex()

    val database: SoliMemoDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            SoliMemoDatabase::class.java,
            "solimemo.db",
        ).addMigrations(
            SoliMemoDatabase.MIGRATION_1_2,
            SoliMemoDatabase.MIGRATION_2_3,
        ).build()
    }

    val noteRepository: NoteRepository by lazy { NoteRepository(database.noteDao()) }

    fun createSyncEngine(accessToken: String): DriveSyncEngine =
        DriveSyncEngine(database.noteDao(), DriveRestClient(accessToken))

    suspend fun synchronize(
        accessToken: String,
        onProgress: (String) -> Unit,
    ): SyncSummary = syncMutex.withLock {
        createSyncEngine(accessToken).synchronize(onProgress)
    }

    fun consumeInitialSyncRequest(): Boolean = initialSyncPending.getAndSet(false)
}
