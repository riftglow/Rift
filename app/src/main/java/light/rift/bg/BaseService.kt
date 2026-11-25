package light.rift.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import light.rift.Action
import light.rift.BootReceiver
import light.rift.R
import light.rift.RiftApp
import light.rift.aidl.IRiftAppService
import light.rift.aidl.IRiftAppServiceCallback
import light.rift.bg.proto.ProxyInstance
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.ktx.*
import light.rift.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import light.rift.core.Protocols
import light.rift.core.utils.SecurityHardening
import light.rift.core.utils.Util
import java.net.UnknownHostException

class BaseService {

    companion object {
        // The currently bound service binder (:bg process). The auto speed test
        // worker runs in :bg too and pushes per-node progress through it; null
        // when the service is not running (broadcast is then a no-op).
        @Volatile
        var currentBinder: Binder? = null
    }

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> runOnMainDispatcher { service.reload() }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (RiftApp.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            if (DataStore.wakeResetConnections && state.canStop) {
                                // 2026-11-06 长息屏断流（小米 14 Pro、纯移动网、未连 WiFi、
                                // 息屏 3h19m）：唤醒后仅 resetAllConnections 救不回来，
                                // 测延时仍断，必须手动切节点才活。切节点走的是 reload，
                                // 说明死掉的状态在只关连接清不掉的东西里（DNS 缓存/复用
                                // 会话/网卡绑定）。唤醒时直接走 reload（与切节点同一条路），
                                // 不再只做 resetAllConnections。state.canStop 保证 VPN
                                // 没开时不会因此自动启动。
                                // 唤醒重建必须全量重建：selector 组只重选不出站重建，
                                // 长息屏后死掉的状态（DNS 缓存/复用会话/网卡绑定）清不掉，
                                // force=true 绕过 canReloadSelector 快捷路径。
                                runOnMainDispatcher { service.reload(force = true) }
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                // 通知栏切分流（2026-11-12，0.2.21）：规则 → 全局 → 直连 → 规则。
                // 通知只在服务运行时存在，state.canStop 为真才 reload，不会误启动。
                // 首页分流控件靠 DataStore 变化监听刷新（见 MainActivity）。
                Action.CYCLE_ROUTE_MODE -> {
                    DataStore.routeMode = when (DataStore.routeMode) {
                        1 -> 2
                        2 -> 0
                        else -> 1
                    }
                    if (service.data.state.canStop) {
                        runOnMainDispatcher { service.reload() }
                    }
                    runOnMainDispatcher { service.data.notification?.refreshActions() }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        // 2026-11-07：串行化 reload。Doze 广播、UI（经 Action.RELOAD 广播）、
        // 快捷方式可能并发触发 reload，无互斥会丢动作或交错启停。
        // 体内不取其他 Mutex：stopRunner/startRunner 只发异步任务、
        // canReloadSelector 只读库，与 Binder.broadcastMutex /
        // ServiceNotification.buildLock 无嵌套，不会死锁。
        val reloadMutex = Mutex()

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : IRiftAppService.Stub(), CoroutineScope,
        AutoCloseable {
        init {
            currentBinder = this
        }

        private val callbacks = object : RemoteCallbackList<IRiftAppServiceCallback>() {
            override fun onCallbackDied(callback: IRiftAppServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<IRiftAppServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: IRiftAppServiceCallback, id: Int) {
            if (id == RiftConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (IRiftAppServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: IRiftAppServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, 3000
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        // Auto speed test progress (step ②, 2026-11-25): oneway callback, a
        // failed push must never break the test round (broadcast swallows).
        fun broadcastTestProgress(profileId: Long, state: Int) = launch {
            broadcast { it.cbTestProgress(profileId, state) }
        }

        override fun close() {
            if (currentBinder == this) currentBinder = null
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        // suspend：reload 体由 data.reloadMutex 串行化；两个调用点都在
        // Data.receiver 里，用 runOnMainDispatcher 包起来（与同一 receiver 里
        // RESET_UPSTREAM_CONNECTIONS 的写法一致）。除这两个调用点外没有其他
        // 直接调用者，外部触发都走 Action.RELOAD 广播，默认行为不变。
        // force=true 跳过 selector 快捷路径、做全量重建（仅唤醒路径用）。
        suspend fun reload(force: Boolean = false) {
            data.reloadMutex.withLock {
                if (DataStore.selectedProxy == 0L) {
                    stopRunner(false, (this as Context).getString(R.string.profile_empty))
                    return@withLock
                }
                if (!force && canReloadSelector()) {
                    val proxy = data.proxy ?: return@withLock
                    val ent = RiftDatabase.proxyDao.getById(DataStore.selectedProxy)
                    val tag = proxy.config.profileTagMap[ent?.id] ?: ""
                    if (tag.isNotBlank() && ent != null) {
                        // select from GUI
                        proxy.box.selectOutbound(tag)
                        // or select from webui
                        // => selector_OnProxySelected
                        return@withLock
                    }
                    // 2026-11-22 -58: tag 查不到就不能静默返回——否则核心还跑
                    // 旧节点、界面却显示新节点名，出口永远顶着旧 IP；掉到
                    // 下面走全量重建，让切换真正发生。
                }
                val s = data.state
                when {
                    s == State.Stopped -> startRunner()
                    s.canStop -> stopRunner(true)
                    else -> Logs.w("Illegal state $s when invoking use")
                }
            }
        }

        fun canReloadSelector(): Boolean {
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = RiftDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            try {
                tmpBox.buildConfigTmp()
            } catch (e: Exception) {
                // N6：旧/坏配置（如残留 WireGuard 节点）在建配置时抛
                // 错，只说明 selector 快捷路径不能用；记日志后返回
                // false，走全量重建，不让异常一路抛到 GlobalScope 里
                // 变成未捕获崩溃。
                Logs.w(e)
                return false
            }
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy?.launch()
                ?: throw IllegalStateException("proxy is null when starting processes")
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        fun killProcesses() {
            // M-1：runOnDefaultDispatcher 的块是 CoroutineScope 接收者，块内 this
            // 是新建的协程 Scope，不是 service；先把 service 存到局部变量再传，
            // 否则 stop 的 key 对不上注册时的 key，监听器永远注销不掉。
            val self = this
            // M-2：每步清理各自 try/catch，任一步抛错都不能跳过后面的清理。
            try {
                data.proxy?.close()
            } catch (e: Exception) {
                Logs.w(e)
            }
            wakeLock?.apply {
                try {
                    release()
                } catch (e: Exception) {
                    Logs.w(e)
                }
                wakeLock = null
            }
            runOnDefaultDispatcher {
                DefaultNetworkListener.stop(self)
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            // 2026-11-07：先查状态再置空。原来先把 DataStore.baseService /
            // vpnService 置 null 再查是否已在停止，一次无操作停止会清掉引用，
            // 之后启动因“无 VpnService”失败。
            if (data.state == State.Stopping) return
            DataStore.baseService = null
            DataStore.vpnService = null

            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    // M-2：每步清理各自 try/catch，任一步抛错都不能跳过后面的
                    // 清理，否则服务卡在 Stopping，再也启动不了。
                    try {
                        killProcesses()
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    val data = data
                    try {
                        if (data.closeReceiverRegistered) {
                            unregisterReceiver(data.receiver)
                            data.closeReceiverRegistered = false
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    data.proxy = null
                }

                // change the state
                try {
                    data.changeState(State.Stopped, msg)
                } finally {
                    // stop the service if nothing has bound to it
                    if (restart) startRunner() else {
                        stopSelf()
                    }
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            DefaultNetworkListener.start(this) {
                RiftApp.connectivity.getLinkProperties(it)?.also { link ->
                    RiftApp.underlyingNetwork = it
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                        if (DataStore.networkChangeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = RiftDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.CYCLE_ROUTE_MODE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = runOnMainDispatcher {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    val rawMsg = exc.readableMessage
                    // 端口死局：随机端口持久化后、起服务时被别的进程抢占，服务起不来，
                    // 而 mixedPort 已不是默认值、ensureInitialized 不再重选，下次启动
                    // 还是同一个坏端口。检测到 bind 冲突就把端口打回默认，下次启动
                    // 重新随机，破掉这个死局。但只对随机分配的端口自动重置：
                    // 用户手动改的端口绝不静默改掉（改了用户下次进设置才发现端口
                    // 变了）；且先确认冲突的确实是 mixed 端口——Clash API 的固定
                    // 9090 冲突时也报这句，重置 mixed 端口治不了它，还白改一个端口。
                    if (rawMsg.contains("address already in use", ignoreCase = true)) {
                        if (DataStore.mixedPortAuto && !SecurityHardening.isPortFree(DataStore.mixedPort)) {
                            Logs.i("mixed port ${DataStore.mixedPort} in use, reset to default ${DataStore.defaultMixedPort}")
                            DataStore.resetMixedPort()
                        } else {
                            Logs.i("bind conflict, mixed port ${DataStore.mixedPort} kept (auto=${DataStore.mixedPortAuto})")
                        }
                    }
                    val friendlyMsg = Protocols.genFriendlyMsg(rawMsg)
                    stopRunner(
                        false,
                        if (friendlyMsg != rawMsg) "${getString(R.string.service_failed)}: $friendlyMsg"
                        else "${getString(R.string.service_failed)}: $rawMsg"
                    )
                } finally {
                    // N2：只在仍是本次 job 时才置空——onStartCommand 重入
                    // （双击启动/系统重发）时，旧 job 的 finally 不能把
                    // 新 job 的引用清掉，否则 stopRunner 再也取消不掉
                    // 正在启动的协程，F4 的旧病以窄窗口复发。
                    // 注意：不能用局部变量存本次 job 再比较——Kotlin 里
                    // 局部 val 不能在自己的初始化 lambda 里引用自己
                    // （#73 的 job 撞 CoroutineContext.job、#74 的
                    // startJob 未解析，都是这个原因）；直接取当前协程
                    // 的 Job 比较即可。
                    if (data.connectingJob === coroutineContext[Job]) data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}