package com.digihori.solimemo

import android.app.Application
import androidx.room.Room
import com.digihori.solimemo.data.local.SoliMemoDatabase
import com.digihori.solimemo.data.repository.NoteRepository
import com.digihori.solimemo.data.remote.DriveRestClient
import com.digihori.solimemo.data.sync.DriveSyncEngine

class SoliMemoApplication : Application() {
    val database: SoliMemoDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            SoliMemoDatabase::class.java,
            "solimemo.db",
        ).build()
    }

    val noteRepository: NoteRepository by lazy { NoteRepository(database.noteDao()) }

    fun createSyncEngine(accessToken: String): DriveSyncEngine =
        DriveSyncEngine(database.noteDao(), DriveRestClient(accessToken))
}
