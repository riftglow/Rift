package light.rift.ui

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import light.rift.database.DataStore
import light.rift.ktx.Logs
import light.rift.ktx.app
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Auto backup (2026-11-10): 5 minutes after a config change, upload the
 * full backup to the configured Nutstore WebDAV. Manual upload/download
 * still exists alongside it.
 *
 * Design, from the FLClash/OwnBox audit:
 * - Debounce: every config change re-enqueues the same unique work with
 *   REPLACE, so the 5-minute clock restarts on each change and a burst
 *   of edits uploads once, not once per edit.
 * - Upload-only, fixed file name rift_backup.json, same content as a
 *   manual full backup (profiles + groups + rules + settings).
 * - History: every auto upload also writes a timestamped copy and
 *   keeps the latest 5 on the server. Auto upload's biggest risks are
 *   overwriting a good backup and losing the old record on failure,
 *   so the history copy goes up FIRST and the fixed file second.
 * - Failure is silent (no popup, no retry storm): the backup page
 *   shows the last auto-backup time and result, and the next config
 *   change re-schedules. A failed run never clobbers the fixed file:
 *   the main PUT throws before any record of success.
 * - Only runs when WebDAV is configured; a missing account/password
 *   is a silent no-op, not an error.
 */
object AutoBackup {

    const val WORK_NAME = "rift_auto_backup"
    const val HISTORY_KEEP = 5

    fun schedule() {
        if (!DataStore.autoBackupEnabled) return
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>()
            .setInitialDelay(5, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
    }

    fun cancel() {
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }
}

class AutoBackupWorker(
    appContext: Context, params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            if (!DataStore.autoBackupEnabled) return Result.success()
            val server = DataStore.webdavServer
            val username = DataStore.webdavUsername
            val password =
                WebDavBackup.decryptPassword(DataStore.webdavPasswordEncrypted)
            if (username.isBlank() || password.isNullOrBlank()) {
                return Result.success()
            }
            val content = BackupContent.build(true, true, true)
            // History copy first (best effort), then the fixed file.
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            runCatching {
                WebDavBackup.uploadTo(
                    server, username, password,
                    "rift_backup_$stamp.json", content
                )
            }
            WebDavBackup.upload(server, username, password, content)
            // Prune history to the latest 5, best effort only.
            runCatching {
                val files = WebDavBackup.listHistoryFiles(server, username, password).sorted()
                files.dropLast(AutoBackup.HISTORY_KEEP).forEach { name ->
                    runCatching {
                        WebDavBackup.deleteFile(server, username, password, name)
                    }
                }
            }
            DataStore.autoBackupLastTime = System.currentTimeMillis()
            DataStore.autoBackupLastResult = "success"
        } catch (e: Exception) {
            Logs.w(e)
            DataStore.autoBackupLastTime = System.currentTimeMillis()
            DataStore.autoBackupLastResult = "failed"
        }
        // Always success: no WorkManager retry. The next config change
        // re-schedules; a retry loop would keep hammering a broken
        // account or a dead network.
        return Result.success()
    }
}
