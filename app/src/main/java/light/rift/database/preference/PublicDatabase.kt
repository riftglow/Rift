package light.rift.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import light.rift.Key
import light.rift.RiftApp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The small public key/value store (single [KeyValuePair] table). It lives
 * in the profile database directory and is used for values that must be
 * readable before the main profile database opens.
 */
@Database(entities = [KeyValuePair::class], version = 1)
@GenerateRoomMigrations
abstract class PublicDatabase : RoomDatabase() {

    /** Key/value DAO for the public store. */
    abstract fun keyValuePairDao(): KeyValuePair.Dao

    companion object {
        /** Lazily built singleton; creates the database directory first. */
        val instance: PublicDatabase by lazy {
            RiftApp.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(
                RiftApp.application,
                PublicDatabase::class.java,
                Key.DB_PUBLIC,
            )
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor { task -> GlobalScope.launch { task.run() } }
                .build()
        }

        /** Shortcut to the singleton's DAO. */
        val kvPairDao get() = instance.keyValuePairDao()
    }

}
