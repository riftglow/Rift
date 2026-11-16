package light.rift.bg.proto

/**
 * Polls the running core for per-outbound traffic counters and turns the
 * raw totals into per-second rates.
 *
 * [items] is the full tag list, including the "bypass" outbound. Each
 * [TrafficLooperData] accumulates its own totals; the [TrafficLooperData]
 * returned by a refresh is only the delta for that round.
 */
class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>,
) {

    /**
     * Counters for one outbound tag. Deliberately not tied to a
     * [light.rift.database.ProxyEntity]: tags are core-side names.
     */
    class TrafficLooperData(
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
    )

    /** Refresh one item and return its delta since the last refresh. */
    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            return item
        }

        val txDelta = box.queryStats(item.tag, "uplink")
        val rxDelta = box.queryStats(item.tag, "downlink")

        item.rx += rxDelta
        item.tx += txDelta
        item.rxRate = rxDelta * 1000 / interval
        item.txRate = txDelta * 1000 / interval

        return TrafficLooperData(
            tag = item.tag,
            rx = rxDelta,
            tx = txDelta,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    /**
     * Refresh every non-ignored item. The core is queried at most once per
     * tag: the first item of a tag runs the query and keeps the delta, and
     * any further item sharing that tag just folds the same delta in.
     */
    suspend fun updateAll() {
        val diffsByTag = mutableMapOf<String, TrafficLooperData>()
        for (item in items) {
            if (item.ignore) continue
            val known = diffsByTag[item.tag]
            if (known == null) {
                diffsByTag[item.tag] = updateOne(item)
            } else {
                item.rx += known.rx
                item.tx += known.tx
                item.rxRate = known.rxRate
                item.txRate = known.txRate
            }
        }
    }

}
