package tk.horiuchi.solimemo.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun syncStateToString(value: SyncState): String = value.name

    @TypeConverter
    fun stringToSyncState(value: String): SyncState = SyncState.valueOf(value)
}

