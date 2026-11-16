package light.rift.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import light.rift.Action
import light.rift.R
import light.rift.RiftApp
import light.rift.aidl.SpeedDisplayData
import light.rift.database.DataStore
import light.rift.database.ProxyEntity
import light.rift.database.RiftDatabase
import light.rift.database.TrafficStatsStore
import light.rift.ktx.app
import light.rift.ktx.getColorAttr
import light.rift.ktx.runOnMainDispatcher
import light.rift.ui.SwitchActivity
import light.rift.utils.Theme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                RiftDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    var listenPostSpeed = true

    // 当前节点名：启动时取自 genTitle，selector 切换时由
    // NativeInterface.selector_OnProxySelected 更新。速度更新时重申一次标题，
    // 保证标题不会在任何一次 builder 改动后丢回旧值或空值。
    private var currentTitle = title

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        // 用量统计（2026-11-12，0.2.21）：展开区加一行“本次 / 今日”。
        // 本次走 SpeedDisplayData 的 session 合计，今日走 TrafficStatsStore
        // （后台进程内内存镜像，无 DB 读）；先在 useBuilder 外算好。
        val (todayTx, todayRx) = TrafficStatsStore.getToday()
        val totalsLine = (service as Context).getString(
            R.string.usage_session_today,
            Formatter.formatFileSize(service, stats.txTotal),
            Formatter.formatFileSize(service, stats.rxTotal),
            Formatter.formatFileSize(service, todayTx),
            Formatter.formatFileSize(service, todayRx)
        )
        useBuilder {
            it.setContentTitle(currentTitle)
            // 2026-11-10：折叠只显示代理一行，展开后直连单独成行
            // （speed_detail 自带 \n 分行；旧写法把两行都塞进 contentText，
            // 折叠态会把直连速度也挤在同一行）。
            val speedSimple = (service as Context).getString(
                R.string.traffic, service.getString(
                    R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                ), service.getString(
                    R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                )
            )
            it.setContentText(speedSimple)
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText("$speedDetail\n$totalsLine"))
            } else {
                it.setStyle(null)
            }
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        currentTitle = newTitle
        useBuilder {
            it.setContentTitle(newTitle)
        }
        update()
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        }
        update()
    }

    // 直连速度开关：每次发通知都现读，不缓存。原来是构造时读一次的 val，
    // 运行中改开关要重启服务才生效。
    private val showDirectSpeed get() = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(RiftApp.configureIntent(service))
        .setSmallIcon(R.drawable.ic_service_active)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        useBuilder {
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 0, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.reset_connections),
                PendingIntent.getBroadcast(
                    service, 0, Intent(Action.RESET_UPSTREAM_CONNECTIONS), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)

            // 分流循环（2026-11-12，0.2.21）：规则 → 全局 → 直连 → 规则，
            // 文案写“切到”的下一个模式，点完由 BaseService 收到广播后切 + reload。
            val nextMode = when (DataStore.routeMode) {
                1 -> 2
                2 -> 0
                else -> 1
            }
            val nextModeName = service.getString(
                when (nextMode) {
                    1 -> R.string.route_mode_global
                    2 -> R.string.route_mode_direct
                    else -> R.string.route_mode_rule
                }
            )
            val cycleRouteAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.route_mode_next, nextModeName),
                PendingIntent.getBroadcast(
                    service, 1, Intent(Action.CYCLE_ROUTE_MODE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(cycleRouteAction)
        }
    }

    /** 分流切换后刷新动作文案并重发通知（BaseService 收到 CYCLE_ROUTE_MODE 后调）。 */
    suspend fun refreshActions() {
        updateActions()
        update()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (service.data.state == BaseService.State.Connected) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
        }
    }


    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    RiftApp.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
