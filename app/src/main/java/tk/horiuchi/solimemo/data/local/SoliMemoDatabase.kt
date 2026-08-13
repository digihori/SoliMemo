package tk.horiuchi.solimemo.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [NoteEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SoliMemoDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
}

