package light.rift.core

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import light.rift.RiftApp
import light.rift.database.preference.KeyValuePair
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * In-memory scratch database for profile-editing sessions: a throwaway
 * [KeyValuePair] store that holds the values being edited until the user
 * saves or discards them. Nothing here is ever persisted to disk.
 */
@Database(entities = [KeyValuePair::class], version = 1)
abstract class TempDatabase : RoomDatabase() {

    /** Key/value DAO for the scratch store. */
    abstract fun profileCacheDao(): KeyValuePair.Dao

    companion object {
        @Suppress("EXPERIMENTAL_API_USAGE")
        private val instance: TempDatabase by lazy {
            Room.inMemoryDatabaseBuilder(RiftApp.application, TempDatabase::class.java)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .setQueryExecutor { task -> GlobalScope.launch { task.run() } }
                .build()
        }

        /** Shortcut to the singleton's DAO. */
        val profileCacheDao get() = instance.profileCacheDao()
    }

}
