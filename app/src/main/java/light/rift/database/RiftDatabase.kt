package light.rift.database

import android.util.Log
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import light.rift.Key
import light.rift.RiftApp
import light.rift.fmt.KryoConverters
import light.rift.fmt.gson.GsonConverters
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Migration 6 -> 7: remove the four plugin protocols (Trojan-Go, Naive, Mieru, Hysteria v1).
 * Drops the trojanGoBean / mieruBean / naiveBean columns from proxy_entities and deletes
 * every row of the removed types (7 = Trojan-Go, 9 = Naive, 21 = Mieru).
 * Uses table recreation instead of ALTER TABLE ... DROP COLUMN so it works on every
 * SQLite version (DROP COLUMN needs SQLite 3.35+).
 * Hysteria v1 rows (type 15, protocolVersion == 1 inside the Kryo blob) cannot be
 * identified by SQL, so they are deleted at runtime by cleanupRemovedProtocols().
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proxy_entities_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`groupId` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                    "`userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, " +
                    "`rx` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                    "`ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, " +
                    "`socksBean` BLOB, `httpBean` BLOB, `ssBean` BLOB, " +
                    "`vmessBean` BLOB, `trojanBean` BLOB, `hysteriaBean` BLOB, " +
                    "`tuicBean` BLOB, `sshBean` BLOB, `wgBean` BLOB, " +
                    "`shadowTLSBean` BLOB, `anyTLSBean` BLOB, `chainBean` BLOB, " +
                    "`nekoBean` BLOB, `configBean` BLOB)"
        )
        db.execSQL(
            "INSERT INTO `proxy_entities_new` " +
                    "(`id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `status`, `ping`, `uuid`, `error`, " +
                    "`socksBean`, `httpBean`, `ssBean`, `vmessBean`, `trojanBean`, `hysteriaBean`, `tuicBean`, " +
                    "`sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `nekoBean`, `configBean`) " +
                    "SELECT `id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `status`, `ping`, `uuid`, `error`, " +
                    "`socksBean`, `httpBean`, `ssBean`, `vmessBean`, `trojanBean`, `hysteriaBean`, `tuicBean`, " +
                    "`sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `nekoBean`, `configBean` " +
                    "FROM `proxy_entities` WHERE `type` NOT IN (7, 9, 21)"
        )
        db.execSQL("DROP TABLE IF EXISTS `proxy_entities`")
        db.execSQL("ALTER TABLE `proxy_entities_new` RENAME TO `proxy_entities`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")
    }
}

/**
 * Migration 7 -> 8: remove NekoBean (TYPE_NEKO = 999, dead plugin holder).
 * Drops the nekoBean column from proxy_entities and deletes every row of
 * type 999. NekoBean only ever held plugin-protocol data (plgId /
 * protocolId / sharedStorage); all four plugin protocols were removed in
 * 0.2.29 -6, so no new NekoBean can be created and displayType() was
 * already "invalid". Uses table recreation like MIGRATION_6_7 (DROP COLUMN
 * needs SQLite 3.35+).
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proxy_entities_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`groupId` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                    "`userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, " +
                    "`rx` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                    "`ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, " +
                    "`socksBean` BLOB, `httpBean` BLOB, `ssBean` BLOB, " +
                    "`vmessBean` BLOB, `trojanBean` BLOB, `hysteriaBean` BLOB, " +
                    "`tuicBean` BLOB, `sshBean` BLOB, `wgBean` BLOB, " +
                    "`shadowTLSBean` BLOB, `anyTLSBean` BLOB, `chainBean` BLOB, " +
                    "`configBean` BLOB)"
        )
        db.execSQL(
            "INSERT INTO `proxy_entities_new` " +
                    "(`id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `status`, `ping`, `uuid`, `error`, " +
                    "`socksBean`, `httpBean`, `ssBean`, `vmessBean`, `trojanBean`, `hysteriaBean`, `tuicBean`, " +
                    "`sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `configBean`) " +
                    "SELECT `id`, `groupId`, `type`, `userOrder`, `tx`, `rx`, `status`, `ping`, `uuid`, `error`, " +
                    "`socksBean`, `httpBean`, `ssBean`, `vmessBean`, `trojanBean`, `hysteriaBean`, `tuicBean`, " +
                    "`sshBean`, `wgBean`, `shadowTLSBean`, `anyTLSBean`, `chainBean`, `configBean` " +
                    "FROM `proxy_entities` WHERE `type` NOT IN (999)"
        )
        db.execSQL("DROP TABLE IF EXISTS `proxy_entities`")
        db.execSQL("ALTER TABLE `proxy_entities_new` RENAME TO `proxy_entities`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")
    }
}

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class RiftDatabase : RoomDatabase() {

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy { openDatabase() }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

        private fun openDatabase(): RiftDatabase {
            ensureDatabaseDirectory()
            val db = Room.databaseBuilder(
                RiftApp.application, RiftDatabase::class.java, Key.DB_PROFILE
            )
//                .addMigrations(*RiftDatabase_Migrations.build())
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
            cleanupRemovedProtocols(db)
            return db
        }

        /**
         * Runtime cleanup for rows the SQL migration cannot identify: old Hysteria v1
         * nodes (type 15, protocolVersion == 1 stored inside the Kryo blob). A v1 row
         * would crash outbound building (HysteriaFmt now requires protocolVersion == 2),
         * so it is deleted on first launch after the upgrade. Also defensively deletes
         * any row of the removed plugin types (7, 9, 21) that somehow survived the
         * migration. Idempotent: after the first run there is nothing left to delete.
         * Runs synchronously here so the rows are gone before any UI reads the node list.
         */
        private fun cleanupRemovedProtocols(db: RiftDatabase) {
            try {
                val dao = db.proxyDao()
                val removed = dao.getAll().filter { entity ->
                    (entity.type == ProxyEntity.TYPE_HYSTERIA &&
                            entity.hysteriaBean?.protocolVersion == 1) ||
                            entity.type == 7 || entity.type == 9 || entity.type == 21
                }
                if (removed.isNotEmpty()) {
                    dao.deleteProxy(removed)
                    Log.i("RiftDatabase", "Removed ${removed.size} node(s) of deleted protocols")
                }
            } catch (e: Exception) {
                Log.w("RiftDatabase", "cleanupRemovedProtocols failed", e)
            }
        }

        private fun ensureDatabaseDirectory() {
            RiftApp.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
        }
    }
}
