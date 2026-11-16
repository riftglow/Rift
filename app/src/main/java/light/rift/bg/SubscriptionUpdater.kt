package light.rift.bg

import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import light.rift.R
import light.rift.core.utils.Util
import light.rift.database.DataStore
import light.rift.database.ProxyGroup
import light.rift.database.RiftDatabase
import light.rift.group.GroupUpdater
import light.rift.ktx.Logs
import light.rift.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val REMINDER_WORK_NAME = "SubscriptionReminder"
    private const val REMINDER_NOTIFICATION_ID_BASE = 10000

    /** 周期任务的节奏：间隔取各订阅里最小的自动更新间隔（最短 15 分钟）。 */
    private data class Schedule(val intervalMinutes: Long, val initialDelaySeconds: Long)

    private fun planSchedule(subscriptions: List<ProxyGroup>): Schedule {
        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay =
            subscriptions.minByOrNull { it.subscription!!.autoUpdateDelay }!!.subscription!!.autoUpdateDelay.toLong()
        val now = System.currentTimeMillis() / 1000L
        // 注意：首次延迟按未取下限的间隔算，下限只影响后面的周期
        var minInitDelay =
            subscriptions.minOf { now - it.subscription!!.lastUpdated - (minDelay * 60) }
        if (minDelay < 15) minDelay = 15
        if (minInitDelay > 60) minInitDelay = 60
        return Schedule(minDelay, minInitDelay)
    }

    suspend fun reconfigureUpdater() {
        // Batch 2 (2026-11-17): the reminder runs daily even when auto-update is off,
        // so it is scheduled unconditionally here (called on group changes + boot).
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            REMINDER_WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(ReminderTask::class.java, 24, TimeUnit.HOURS).build()
        )

        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val subscriptions = RiftDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        if (subscriptions.isEmpty()) return

        val schedule = planSchedule(subscriptions)

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, schedule.intervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (schedule.initialDelaySeconds > 0) {
                        setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                    }
                }
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                RiftDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(profile, false)
            }

            nm.cancel(2)

            return Result.success()
        }
    }

    /**
     * Batch 2 (2026-11-17): daily subscription reminder — expiry within 3 days,
     * or remaining traffic below 10%. The expiry reminder fires at most once a
     * day; the traffic reminder fires once per low-traffic episode (the marker
     * clears when the remaining traffic recovers above 15%).
     */
    class ReminderTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        private val nm = NotificationManagerCompat.from(applicationContext)

        private fun notify(group: ProxyGroup, text: String) {
            val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
                .setContentTitle(applicationContext.getString(R.string.subscription_reminder_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_service_active)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setAutoCancel(true)
                .build()
            nm.notify(REMINDER_NOTIFICATION_ID_BASE + group.id.toInt(), notification)
        }

        // Returns (used, total, expireEpochSeconds); total/expire are 0 when unknown.
        private fun parseUserinfo(info: String): Triple<Long, Long, Long> {
            fun get(regex: String): Long? =
                regex.toRegex().find(info)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val used = (get("upload=([0-9]+)") ?: 0L) + (get("download=([0-9]+)") ?: 0L)
            return Triple(used, get("total=([0-9]+)") ?: 0L, get("expire=([0-9]+)") ?: 0L)
        }

        override suspend fun doWork(): Result {
            val nowSec = System.currentTimeMillis() / 1000L
            val todayDay = System.currentTimeMillis() / 86400000L
            for (group in RiftDatabase.groupDao.subscriptions()) {
                val subscription = group.subscription ?: continue
                var used: Long
                var total: Long
                var expire: Long
                if (subscription.bytesUsed > 0L) {
                    // SIP008 / Open Online Config: bytesRemaining 0 means unknown, not empty.
                    used = subscription.bytesUsed
                    total = if (subscription.bytesRemaining > 0L) {
                        subscription.bytesUsed + subscription.bytesRemaining
                    } else 0L
                    expire = 0L
                } else if (!subscription.subscriptionUserinfo.isNullOrBlank()) {
                    val parsed = parseUserinfo(subscription.subscriptionUserinfo!!)
                    used = parsed.first
                    total = parsed.second
                    expire = parsed.third
                } else continue

                if (expire > 0L && expire - nowSec <= 3 * 86400L) {
                    val key = "subReminderExpireDay_${group.id}"
                    if (DataStore.configurationStore.getLong(key, -1L) != todayDay) {
                        notify(
                            group,
                            applicationContext.getString(
                                R.string.subscription_reminder_expire,
                                group.displayName(),
                                Util.timeStamp2Text(expire * 1000)
                            )
                        )
                        DataStore.configurationStore.putLong(key, todayDay)
                    }
                }
                if (total > 0L) {
                    val remain = total - used
                    val key = "subReminderTraffic_${group.id}"
                    if (remain < total / 10) {
                        if (DataStore.configurationStore.getLong(key, 0L) == 0L) {
                            notify(
                                group,
                                applicationContext.getString(
                                    R.string.subscription_reminder_traffic,
                                    group.displayName(),
                                    Formatter.formatFileSize(applicationContext, remain)
                                )
                            )
                            DataStore.configurationStore.putLong(key, 1L)
                        }
                    } else if (remain >= total * 15 / 100) {
                        DataStore.configurationStore.putLong(key, 0L)
                    }
                }
            }
            return Result.success()
        }
    }

}
