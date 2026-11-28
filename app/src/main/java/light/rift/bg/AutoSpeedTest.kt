package light.rift.bg

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import light.rift.GroupOrder
import light.rift.R
import light.rift.RiftApp
import light.rift.bg.proto.UrlTest
import light.rift.database.DataStore
import light.rift.database.GroupManager
import light.rift.database.ProxyEntity
import light.rift.database.RiftDatabase
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ktx.isIpAddress
import light.rift.ktx.readableMessage

/**
 * Step ② (2026-11-25): auto speed test — periodically test every node in the
 * current group (URL by default, TCP optional) and sort the group by delay.
 *
 * Runs in the `:bg` process (WorkManager's default process is `:bg`, see
 * RiftApp.getWorkManagerConfiguration). Two hard rules from the -59/-66
 * lessons: (1) `:bg` DB writes never notify the main-process adapter, so live
 * per-node progress goes out over the AIDL `cbTestProgress` callback, not the
 * DB; (2) a background writer must re-read a row and touch only its own
 * columns — never write back a start-of-run entity.
 */
object AutoSpeedTest {

    const val WORK_NAME = "rift_auto_speed_test"

    /** Cross-process mutex (Room key): "auto:<ts>" / "manual:<ts>". */
    const val MUTEX_KEY = "autoSpeedTestRun"

    /** A stale mutex (worker killed mid-round) stops blocking after 10 min. */
    const val MUTEX_TTL_MS = 10 * 60 * 1000L

    // AIDL cbTestProgress states (must match the UI side).
    const val STATE_DONE = 0
    const val STATE_TESTING = 1
    const val STATE_QUEUED = 2
    const val ROUND_DONE_ID = -1L

    /** (Re)schedule the periodic worker from the current settings. */
    suspend fun reconfigure() {
        val wm = RemoteWorkManager.getInstance(app)
        if (!DataStore.autoSpeedTestEnabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val interval = DataStore.autoSpeedTestInterval.coerceAtLeast(15).toLong()
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(AutoSpeedTestWorker::class.java, interval, TimeUnit.MINUTES)
                .build()
        )
    }

    private fun broadcastProgress(profileId: Long, state: Int) {
        try {
            BaseService.currentBinder?.broadcastTestProgress(profileId, state)
        } catch (e: Exception) {
            // Oneway push must never break the test round.
            Logs.w(e)
        }
    }

    /** -66-safe write-back: re-read the row, change only status/ping/error. */
    private fun writeResult(id: Long, status: Int, ping: Int, error: String?) {
        try {
            val fresh = RiftDatabase.proxyDao.getById(id) ?: return // deleted meanwhile
            fresh.status = status
            fresh.ping = ping
            fresh.error = error
            RiftDatabase.proxyDao.updateProxy(fresh)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /**
     * TCP dial, same logic as ConfigurationFragment.pingTest (kept as a
     * separate copy so the manual path is untouched). Returns ping in ms.
     */
    private fun tcpPing(profile: ProxyEntity): Int {
        var address = profile.requireBean().serverAddress
        if (!address.isIpAddress()) {
            try {
                RiftApp.underlyingNetwork!!.getAllByName(address).apply {
                    if (isNotEmpty()) address = this[0].hostAddress
                }
            } catch (ignored: UnknownHostException) {
            }
        }
        if (!address.isIpAddress()) throw UnknownHostException(address)
        val socket = RiftApp.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.soTimeout = 3000
            socket.bind(InetSocketAddress(0))
            val start = SystemClock.elapsedRealtime()
            socket.connect(
                InetSocketAddress(address, profile.requireBean().serverPort), 3000
            )
            return (SystemClock.elapsedRealtime() - start).toInt()
        } finally {
            socket.closeQuietly()
        }
    }

    /** Same error mapping as pingTest's catch block. */
    private fun mapTcpError(e: Exception): Pair<Int, String?> {
        if (e is UnknownHostException) {
            return 2 to app.getString(R.string.connection_test_domain_not_found)
        }
        val message = e.readableMessage
        return when {
            !message.contains("failed:") ->
                2 to app.getString(R.string.connection_test_timeout)
            message.contains("ECONNREFUSED") ->
                2 to app.getString(R.string.connection_test_refused)
            message.contains("ENETUNREACH") ->
                2 to app.getString(R.string.connection_test_unreachable)
            else -> 3 to message
        }
    }

    class AutoSpeedTestWorker(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            // Gate 1: switch on.
            if (!DataStore.autoSpeedTestEnabled) return Result.success()
            // Gate 2: VPN connected (accurate in :bg — BaseService writes it).
            if (!DataStore.serviceState.connected) return Result.success()
            // Gate 3: network. No active network -> skip; WiFi-only -> skip on cellular.
            if (!networkOk()) return Result.success()
            // Mutex: a manual batch running in the main process blocks this round.
            // -79 B-I3：原子抢占（读-判-写包在一个 SQLite 写事务里），
            // 不再先读再写——两个进程同时读到空键会双双开跑。
            val now = System.currentTimeMillis()
            if (!DataStore.configurationStore.tryAcquireTestMutex(
                    MUTEX_KEY, "auto:$now", "manual:", now, MUTEX_TTL_MS
                )
            ) return Result.success()
            try {
                runRound()
            } finally {
                // Only clear our own key — a manual batch may have taken it over.
                val cur = DataStore.configurationStore.getString(MUTEX_KEY, "") ?: ""
                if (cur == "auto:$now") DataStore.configurationStore.putString(MUTEX_KEY, "")
            }
            return Result.success()
        }

        private fun networkOk(): Boolean {
            val cm = RiftApp.connectivity
            val network = cm.activeNetwork ?: return false
            if (!DataStore.autoSpeedTestWifiOnly) return true
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        private suspend fun runRound() {
            // Snapshot the group now — a mid-round group switch must not split
            // the round across two groups.
            val group = DataStore.currentGroup()
            val mode = DataStore.autoSpeedTestMode
            try {
                val profilesList = RiftDatabase.proxyDao.getByGroup(group.id).let { list ->
                    if (mode == "tcp") {
                        list.filter {
                            // -79 B-I4：坏记录逐条跳过。原先 requireBean()
                            // 在 try 外面，一条坏记录抛异常就杀死整轮、
                            // 连 ROUND_DONE 都不发，UI 永远卡在“测试中”。
                            runCatching { it.requireBean().canTCPing() }
                                .onFailure { Logs.w(it) }
                                .getOrDefault(false)
                        }
                    } else {
                        list
                    }
                }
                if (profilesList.isEmpty()) return
                profilesList.forEach { broadcastProgress(it.id, STATE_QUEUED) }
                val queue = ConcurrentLinkedQueue(profilesList)
                coroutineScope {
                    repeat(DataStore.connectionTestConcurrent) {
                        launch(Dispatchers.IO) {
                            val urlTest = UrlTest()
                            while (isActive) {
                                val profile = queue.poll() ?: break
                                broadcastProgress(profile.id, STATE_TESTING)
                                if (mode == "tcp") {
                                    try {
                                        writeResult(profile.id, 1, tcpPing(profile), null)
                                    } catch (e: CancellationException) {
                                        // -79 B-I2：取消不是失败。原先这里被
                                        // catch (e: Exception) 吞掉、写成一
                                        // 条失败结果，把好的延迟清成 0。
                                        // 重新抛出：coroutineScope 会跳过排
                                        // 序与 lastTime，finally 发 ROUND_DONE，
                                        // doWork 的 finally 清互斥，WorkManager
                                        // 看到的是取消、不是假成功。
                                        throw e
                                    } catch (e: Exception) {
                                        val (status, err) = mapTcpError(e)
                                        writeResult(profile.id, status, 0, err)
                                    }
                                } else {
                                    try {
                                        writeResult(profile.id, 1, urlTest.doTest(profile), null)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        writeResult(profile.id, 3, 0, e.readableMessage)
                                    }
                                }
                                broadcastProgress(profile.id, STATE_DONE)
                            }
                        }
                    }
                }
                // Sort the group by delay (once per round; updateGroup also
                // schedules one auto-backup — do NOT call it per node).
                if (group.order != GroupOrder.BY_DELAY) {
                    group.order = GroupOrder.BY_DELAY
                    GroupManager.updateGroup(group)
                }
                DataStore.autoSpeedTestLastTime = System.currentTimeMillis()
            } finally {
                // Round over (or killed): let the UI clear its transient states
                // and reload once with whatever results landed.
                broadcastProgress(ROUND_DONE_ID, STATE_DONE)
            }
        }
    }
}
