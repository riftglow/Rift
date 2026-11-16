package light.rift.bg.proto

import light.rift.aidl.SpeedDisplayData
import light.rift.aidl.TrafficData
import light.rift.bg.BaseService
import light.rift.bg.RiftConnection
import light.rift.database.DataStore
import light.rift.database.ProfileManager
import light.rift.database.ProxyEntity
import light.rift.database.TrafficStatsStore
import light.rift.fmt.TAG_BYPASS
import light.rift.fmt.TAG_PROXY
import light.rift.ktx.Logs
import light.rift.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.*

class TrafficLooper
    (
    val data: BaseService.Data, private val sc: CoroutineScope
) {

    private var job: Job? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>() // id to 1 data
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>() // tag to 1 data

    // 用量统计（2026-11-12，0.2.21）：本轮相对上一轮的增量先攒着，
    // 60 秒刷一次盘，停服务时把余量刷掉。mainTx/mainRx 是本会话累计，
    // 重启后会从 0 重算（current < last 时增量就按 current 计，不倒扣）。
    private var lastMainTx = 0L
    private var lastMainRx = 0L
    private var pendingTx = 0L
    private var pendingRx = 0L
    private var lastFlushAt = 0L

    // 清除时间点（2026-11-12，0.2.21 -3）：界面点“清除流量统计”后
    // DataStore.trafficClearEpoch 更新；后台进程每轮看一眼，见 onClearObserved()。
    private var lastSeenClearEpoch = 0L

    // 波形历史（2026-11-12，0.2.21 -5）：每轮记一个下行速率，只留最近
    // 60 个；每 60 秒刷盘时写进 DataStore，界面打开首页时读出来当波形
    // 初始样本——后台（YouTube 等）流过的量也有记录，不再从空开始。
    private val sparklineHistory = ArrayDeque<Long>(60)

    // 速度平滑（2026-11-13，0.2.24 -9）：瞬时速率每秒一跳（YouTube
    // 缓冲时忽高忽低），看着抖。取最近 3 轮的平均当显示速率，
    // 3 秒窗口：变化跟得上，抖动压下去。状态卡、节点列表、通知、
    // 波形都读这里的平滑值，口径一致。
    private val txRateWindow = ArrayDeque<Long>(3)
    private val rxRateWindow = ArrayDeque<Long>(3)

    suspend fun stop() {
        job?.cancel()
        // 0.2.21 -3：刚清过就先回落内存状态——待刷量丢掉，各节点累计清零，
        // 后面的回写就写 0，不把旧数写回库。
        onClearObserved()
        // 用量统计：停服务时把没刷盘的余量写掉（不依赖 profileTrafficStatistics，
        // 每日桶是独立功能；写失败只记日志，不挡后面的 per-profile 回写）。
        if (pendingTx > 0 || pendingRx > 0) {
            val flushTx = pendingTx
            val flushRx = pendingRx
            pendingTx = 0L
            pendingRx = 0L
            try {
                TrafficStatsStore.addTraffic(flushTx, flushRx)
            } catch (e: Exception) {
                Logs.e("TrafficLooper.stop: flush daily traffic failed", e)
            }
        }
        // finally traffic post
        if (!DataStore.profileTrafficStatistics) return
        val traffic = mutableMapOf<Long, TrafficData>()
        data.proxy?.config?.trafficMap?.forEach { (_, ents) ->
            for (ent in ents) {
                val item = idMap[ent.id] ?: return@forEach
                ent.rx = item.rx
                ent.tx = item.tx
                ProfileManager.updateProfile(ent) // update DB
                traffic[ent.id] = TrafficData(
                    id = ent.id,
                    rx = ent.rx,
                    tx = ent.tx,
                )
            }
        }
        data.binder.broadcast { b ->
            for (t in traffic) {
                b.cbTrafficUpdate(t.value)
            }
        }
        Logs.d("finally traffic post done")
    }

    /**
     * 发现新的清除就回落内存状态，返回 true；没变化返回 false。
     * 清之前攒的待刷量直接丢掉；:bg 进程里 TrafficStatsStore 的内存镜像
     * 失效，下次写盘从库重载（空桶），不然旧 map 又把整桶写回来；
     * 各节点内存累计清零重算，停服务/切节点时不再把旧数写回库。
     * 只在 looper 协程里调用（loop 每轮开头、stop 开头），idMap 不是线程安全的。
     */
    private fun onClearObserved(): Boolean {
        val epoch = DataStore.trafficClearEpoch
        if (epoch <= lastSeenClearEpoch) return false
        lastSeenClearEpoch = epoch
        pendingTx = 0L
        pendingRx = 0L
        // 0.2.21 -5：波形历史也清掉（存盘的 JSON 由界面清动作一并清，
        // 这里清内存队列，别让下一轮刷盘又把清之前的波形写回去）。
        sparklineHistory.clear()
        TrafficStatsStore.refresh()
        for (item in idMap.values) {
            item.tx = 0L
            item.rx = 0L
            item.txBase = 0L
            item.rxBase = 0L
        }
        lastMainTx = 0L
        lastMainRx = 0L
        return true
    }

    fun start() {
        job = sc.launch { loop() }
    }

    var selectorNowId = -114514L
    var selectorNowFakeTag = ""

    fun selectMain(id: Long) {
        Logs.d("select traffic count $TAG_PROXY to $id, old id is $selectorNowId")
        val oldData = idMap[selectorNowId]
        val newData = idMap[id] ?: return
        oldData?.apply {
            tag = selectorNowFakeTag
            ignore = true
            // post traffic when switch
            if (DataStore.profileTrafficStatistics) {
                data.proxy?.config?.trafficMap?.get(tag)?.firstOrNull()?.let {
                    it.rx = rx
                    it.tx = tx
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(it) // update DB
                    }
                }
            }
        }
        selectorNowFakeTag = newData.tag
        selectorNowId = id
        newData.apply {
            tag = TAG_PROXY
            ignore = false
        }
    }

    private suspend fun loop() {
        val delayMs = DataStore.speedInterval.toLong()
        val showDirectSpeed = DataStore.showDirectSpeed
        val profileTrafficStatistics = DataStore.profileTrafficStatistics
        if (delayMs == 0L) return

        var trafficUpdater: TrafficUpdater? = null
        var proxy: ProxyInstance?

        // for display
        val itemBypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

        while (sc.isActive) {
            // 0.2.21 -3：界面刚清过流量统计就先回落内存状态，再算这一轮的增量。
            onClearObserved()
            proxy = data.proxy
            if (proxy == null) {
                delay(delayMs)
                continue
            }

            if (trafficUpdater == null) {
                if (!proxy.isInitialized()) continue
                idMap.clear()
                idMap[-1] = itemBypass
                //
                val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
                proxy.config.trafficMap.forEach { (tag, ents) ->
                    tags.add(tag)
                    for (ent in ents) {
                        val item = TrafficUpdater.TrafficLooperData(
                            tag = tag,
                            rx = ent.rx,
                            tx = ent.tx,
                            rxBase = ent.rx,
                            txBase = ent.tx,
                            ignore = proxy.config.selectorGroupId >= 0L,
                        )
                        idMap[ent.id] = item
                        tagMap[tag] = item
                        Logs.d("traffic count $tag to ${ent.id}")
                    }
                }
                if (proxy.config.selectorGroupId >= 0L) {
                    selectMain(proxy.config.mainEntId)
                }
                //
                trafficUpdater = TrafficUpdater(
                    box = proxy.box, items = idMap.values.toList()
                )
                proxy.box.setV2rayStats(tags.joinToString("\n"))
            }

            trafficUpdater.updateAll()
            if (!sc.isActive) return

            // add all non-bypass to "main"
            var mainTxRate = 0L
            var mainRxRate = 0L
            var mainTx = 0L
            var mainRx = 0L
            tagMap.forEach { (_, it) ->
                if (!it.ignore) {
                    mainTxRate += it.txRate
                    mainRxRate += it.rxRate
                }
                mainTx += it.tx - it.txBase
                mainRx += it.rx - it.rxBase
            }

            // 速度平滑（0.2.24 -9）：最近 3 轮取平均。窗口刚起时只有 1 个
            // 样本，平均就是它自己，3 秒后满窗才真正平滑；断流回落到 0
            // 也要 3 秒才落到底，这是平滑的代价，窗口短可接受。
            if (txRateWindow.size >= 3) txRateWindow.removeFirst()
            txRateWindow.addLast(mainTxRate.coerceAtLeast(0L))
            if (rxRateWindow.size >= 3) rxRateWindow.removeFirst()
            rxRateWindow.addLast(mainRxRate.coerceAtLeast(0L))
            val smoothTxRate = txRateWindow.average().toLong()
            val smoothRxRate = rxRateWindow.average().toLong()

            // 用量统计（0.2.21）：本轮增量进待写缓存，60 秒刷一次盘。
            // 刷盘走 runOnDefaultDispatcher 异步写，不堵这条每秒循环。
            val dTx = if (mainTx < lastMainTx) mainTx else mainTx - lastMainTx
            val dRx = if (mainRx < lastMainRx) mainRx else mainRx - lastMainRx
            lastMainTx = mainTx
            lastMainRx = mainRx
            if (dTx > 0 || dRx > 0) {
                pendingTx += dTx
                pendingRx += dRx
            }
            // 波形历史（0.2.21 -5）：每轮记一个下行速率，只留最近 60 个。
            // 记平滑值：波形是看走势的，平滑后走势更稳。
            if (sparklineHistory.size >= 60) sparklineHistory.removeFirst()
            sparklineHistory.addLast(smoothRxRate)
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFlushAt >= 60_000L && (pendingTx > 0 || pendingRx > 0)) {
                lastFlushAt = nowMs
                val flushTx = pendingTx
                val flushRx = pendingRx
                pendingTx = 0L
                pendingRx = 0L
                runOnDefaultDispatcher { TrafficStatsStore.addTraffic(flushTx, flushRx) }
                // 0.2.21 -4：顺手把各节点 tx/rx 写库。loop 里各节点只在内存
                // idMap 累加，原先只在切节点（selectMain）/停服务（stop）时
                // 写库；清完后一直用同一个节点不切不重启，库里一直是 0，
                // 用量页读库就一直显示“还没有流量记录”。
                // 快照在 looper 线程里做（idMap 不是线程安全的），只把写库
                // 派到 DefaultDispatcher；只写非 ignore 且有流量的当前在用
                // 节点（链式只写链本身，成员 ignore=true 不写；bypass 没有
                // 对应 ProxyEntity，不在 trafficMap 里）。写库只在
                // profileTrafficStatistics 开着时做，和 selectMain/stop
                // 的写库口径一致。
                if (profileTrafficStatistics) {
                    val toUpdate = mutableListOf<ProxyEntity>()
                    proxy?.config?.trafficMap?.forEach { (_, ents) ->
                        for (ent in ents) {
                            val item = idMap[ent.id] ?: continue
                            if (!item.ignore && (item.tx > 0L || item.rx > 0L)) {
                                ent.rx = item.rx
                                ent.tx = item.tx
                                toUpdate.add(ent)
                            }
                        }
                    }
                    if (toUpdate.isNotEmpty()) {
                        runOnDefaultDispatcher { ProfileManager.updateProfile(toUpdate) }
                    }
                }
                // 0.2.21 -5：顺手把最近 60 个下行速率写进 DataStore，供首页
                // 波形图当初始样本。快照在 looper 线程里做（ArrayDeque 不是
                // 线程安全的），只把写盘派到 DefaultDispatcher；不依赖
                // profileTrafficStatistics，波形是速度显示，和每日桶同一口径。
                val historySnapshot = sparklineHistory.toList()
                runOnDefaultDispatcher { TrafficStatsStore.saveSparklineHistory(historySnapshot) }
            }

            // speed：主路速率用平滑值；直连速率量小、只在通知里露，
            // 保持瞬时值不动。
            val speed = SpeedDisplayData(
                smoothTxRate,
                smoothRxRate,
                if (showDirectSpeed) itemBypass.txRate else 0L,
                if (showDirectSpeed) itemBypass.rxRate else 0L,
                mainTx,
                mainRx
            )

            // broadcast (MainActivity)
            if (data.state == BaseService.State.Connected
                && data.binder.callbackIdMap.containsValue(RiftConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            ) {
                data.binder.broadcast { b ->
                    if (data.binder.callbackIdMap[b] == RiftConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                        b.cbSpeedUpdate(speed)
                        if (profileTrafficStatistics) {
                            idMap.forEach { (id, item) ->
                                b.cbTrafficUpdate(
                                    TrafficData(id = id, rx = item.rx, tx = item.tx) // display
                                )
                            }
                        }
                    }
                }
            }

            // ServiceNotification
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }

            delay(delayMs)
        }
    }
}