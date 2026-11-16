package light.rift.database

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import light.rift.database.preference.PublicDatabase
import light.rift.ktx.Logs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 用量统计（2026-11-12，0.2.21）：每日流量桶。
 *
 * 一天一个桶（yyyy-MM-dd -> {tx, rx}），整个 map 存成一条 JSON 放在
 * DataStore.trafficDailyJson，只留最近 60 天。桶里记的是全部代理流量
 * （直连不计，与通知栏/状态卡的 mainTx/mainRx 同一口径）。
 *
 * 进程内有一份内存镜像：写进程（后台服务）靠 addTraffic 保持同步，
 * 读进程（界面）显示前调 refresh() 从 DataStore 重载——跨进程不共享内存。
 */
object TrafficStatsStore {

    data class DayTraffic(var tx: Long = 0L, var rx: Long = 0L)

    private val gson = Gson()
    // 匿名 object : TypeToken<...>() 靠类文件里的泛型签名取类型，
    // R8  release 会把这份签名剥掉，类初始化就抛 Missing type parameter
    // （0.2.21 预览闪退根因）。改用 getParameterized 编程式拼类型，不依赖签名。
    private val mapType: java.lang.reflect.Type = TypeToken.getParameterized(
        LinkedHashMap::class.java, String::class.java, DayTraffic::class.java
    ).type
    // 波形历史（2026-11-12，0.2.21 -5）：最近 60 个下行速率的 JSON 数组。
    // List<Long> 同样用 getParameterized 拼类型，不用匿名 TypeToken。
    private val sparklineListType: java.lang.reflect.Type = TypeToken.getParameterized(
        ArrayList::class.java, java.lang.Long::class.java
    ).type
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val lock = Any()
    private var cached: LinkedHashMap<String, DayTraffic>? = null

    /** 内存镜像失效，下次读重新从 DataStore 加载（界面进程显示前调用）。 */
    fun refresh() {
        synchronized(lock) { cached = null }
    }

    private fun loadLocked(): LinkedHashMap<String, DayTraffic> {
        cached?.let { return it }
        val map = try {
            val json = DataStore.trafficDailyJson
            if (json.isBlank()) LinkedHashMap()
            else gson.fromJson<LinkedHashMap<String, DayTraffic>>(json, mapType)
                ?: LinkedHashMap()
        } catch (e: Exception) {
            Logs.e("TrafficStatsStore: bad trafficDailyJson, reset", e)
            // 2026-11-14，0.2.24 -19，L-30：坏串之前只记日志不清掉，
            // 每次进程重启都重新解析失败一次；清掉，下次直接走空表。
            DataStore.trafficDailyJson = ""
            LinkedHashMap()
        }
        cached = map
        return map
    }

    private fun saveLocked(map: LinkedHashMap<String, DayTraffic>) {
        cached = map
        try {
            DataStore.trafficDailyJson = gson.toJson(map)
        } catch (e: Exception) {
            Logs.e("TrafficStatsStore: save failed", e)
        }
    }

    /** 累加到今天的桶；顺手清掉 60 天以前的桶。 */
    fun addTraffic(txDelta: Long, rxDelta: Long) {
        if (txDelta <= 0 && rxDelta <= 0) return
        synchronized(lock) {
            val map = loadLocked()
            val today = LocalDate.now().format(dateFmt)
            val day = map.getOrPut(today) { DayTraffic() }
            day.tx += txDelta
            day.rx += rxDelta
            pruneLocked(map)
            saveLocked(map)
        }
    }

    private fun pruneLocked(map: MutableMap<String, DayTraffic>) {
        // yyyy-MM-dd 字典序即时间序。
        val cutoff = LocalDate.now().minusDays(60).format(dateFmt)
        map.keys.removeAll { it < cutoff }
    }

    /** 清空全部每日桶（首页“清除流量统计”用）；同时记一个清除时间点，
     * 让后台服务的 TrafficLooper 丢掉清之前攒的待刷量、把内存累计清零，
     * 不然它下次刷盘又把旧数写回来。先写空桶再写时间点。波形历史也一并
     * 清掉（0.2.21 -5），不然界面还会把清之前的波形种回来。 */
    fun clearAll() {
        synchronized(lock) {
            cached = LinkedHashMap()
            try {
                // 2026-11-14，0.2.24 -19，L-30：三写包进同一事务，进程在
                // 写 1 与写 3 之间被杀不再留下不一致状态（空桶却无清除时间点）。
                PublicDatabase.instance.runInTransaction {
                    DataStore.trafficDailyJson = ""
                    DataStore.sparklineHistoryJson = ""
                    DataStore.trafficClearEpoch = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Logs.e("TrafficStatsStore: clear failed", e)
            }
        }
    }

    /** 波形历史（0.2.21 -5）：service 侧每 60 秒把最近 60 个下行速率
     *（bytes/s）写进 DataStore，供首页波形图当初始样本。 */
    fun saveSparklineHistory(samples: List<Long>) {
        try {
            DataStore.sparklineHistoryJson = gson.toJson(samples)
        } catch (e: Exception) {
            Logs.e("TrafficStatsStore: save sparkline history failed", e)
        }
    }

    /** 波形历史（0.2.21 -5）：界面侧读最近 60 个下行速率；没有记录
     * 或解析失败返回空表。跨进程读走 Room，直读即最新，不走内存镜像。 */
    fun getSparklineHistory(): List<Long> {
        return try {
            val json = DataStore.sparklineHistoryJson
            if (json.isBlank()) emptyList()
            else gson.fromJson<ArrayList<Long>>(json, sparklineListType) ?: emptyList()
        } catch (e: Exception) {
            Logs.e("TrafficStatsStore: bad sparklineHistoryJson", e)
            // 2026-11-14，0.2.24 -19，L-30：同 loadLocked，坏串清掉，
            // 不然每次读都重新解析失败一次。
            DataStore.sparklineHistoryJson = ""
            emptyList()
        }
    }

    fun getToday(): Pair<Long, Long> {
        synchronized(lock) {
            val day = loadLocked()[LocalDate.now().format(dateFmt)] ?: return 0L to 0L
            return day.tx to day.rx
        }
    }

    /** 本期开始日期：结算日那天；今天还没到结算日就从上月结算日算起。 */
    fun periodStart(billingDay: Int): LocalDate {
        val day = billingDay.coerceIn(1, 28)
        val today = LocalDate.now()
        val start = today.withDayOfMonth(minOf(day, today.lengthOfMonth()))
        return if (start.isAfter(today)) start.minusMonths(1) else start
    }

    /** 本期：从结算日那天起（含）到今天。billingDay 1-28，越界按 1/28 夹住。 */
    fun getThisPeriod(billingDay: Int): Pair<Long, Long> {
        val today = LocalDate.now()
        var tx = 0L
        var rx = 0L
        synchronized(lock) {
            val map = loadLocked()
            var d = periodStart(billingDay)
            while (!d.isAfter(today)) {
                map[d.format(dateFmt)]?.let { tx += it.tx; rx += it.rx }
                d = d.plusDays(1)
            }
        }
        return tx to rx
    }

    /** 最近 30 天（含今天），按日期升序；没有记录的天为 0。 */
    fun getLast30Days(): List<Pair<String, Long>> {
        val today = LocalDate.now()
        val out = ArrayList<Pair<String, Long>>(30)
        synchronized(lock) {
            val map = loadLocked()
            for (i in 29 downTo 0) {
                val key = today.minusDays(i.toLong()).format(dateFmt)
                val day = map[key]
                out.add(key to ((day?.tx ?: 0L) + (day?.rx ?: 0L)))
            }
        }
        return out
    }
}
