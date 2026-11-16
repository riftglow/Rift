package light.rift.ui

import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.database.TrafficStatsStore
import light.rift.ktx.getColorAttr
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.widget.UsageChartView

/**
 * 用量统计页（2026-11-12，0.2.21）：今日 / 本期（可设结算日）/
 * 最近 30 天柱状图 / 各节点累计流量。抽屉第二组（工具组）。
 *
 * 每节点流量取库里的累计 tx/rx：0.2.21 起链式只记链本身一次，
 * 但 0.2.21 之前已记下的三倍数还在库里，不会自动清掉——
 * 想从干净数开始，用首页菜单里的“清除流量统计”清一次。
 */
class UsageFragment : ToolbarFragment(R.layout.layout_usage) {

    private lateinit var todayValue: TextView
    private lateinit var periodValue: TextView
    private lateinit var periodSub: TextView
    private lateinit var billingValue: TextView
    private lateinit var chart: UsageChartView
    private lateinit var nodeList: LinearLayout
    private lateinit var nodeEmpty: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_usage)

        todayValue = view.findViewById(R.id.usage_today_value)
        periodValue = view.findViewById(R.id.usage_period_value)
        periodSub = view.findViewById(R.id.usage_period_sub)
        billingValue = view.findViewById(R.id.usage_billing_value)
        chart = view.findViewById(R.id.usage_chart)
        nodeList = view.findViewById(R.id.usage_node_list)
        nodeEmpty = view.findViewById(R.id.usage_node_empty)

        view.findViewById<View>(R.id.usage_billing_row).setOnClickListener {
            showBillingDayPicker()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!isAdded) return
        // 后台服务在另一个进程写桶，界面显示前重载一次。
        TrafficStatsStore.refresh()

        val (todayTx, todayRx) = TrafficStatsStore.getToday()
        todayValue.text = trafficText(todayTx, todayRx)

        val billingDay = DataStore.trafficBillingDay
        val (periodTx, periodRx) = TrafficStatsStore.getThisPeriod(billingDay)
        periodValue.text = trafficText(periodTx, periodRx)
        val start = TrafficStatsStore.periodStart(billingDay)
        periodSub.text = getString(
            R.string.usage_period_from,
            start.monthValue, start.dayOfMonth
        )
        billingValue.text = getString(R.string.usage_billing_day_value, billingDay)

        chart.setData(TrafficStatsStore.getLast30Days())
        refreshNodeList()
    }

    private fun trafficText(tx: Long, rx: Long): String {
        val ctx = requireContext()
        return getString(
            R.string.traffic,
            Formatter.formatFileSize(ctx, tx),
            Formatter.formatFileSize(ctx, rx)
        )
    }

    private fun refreshNodeList() {
        runOnDefaultDispatcher {
            val profiles = RiftDatabase.proxyDao.getAll()
                .filter { it.tx > 0L || it.rx > 0L }
                .sortedByDescending { it.tx + it.rx }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                nodeList.removeAllViews()
                nodeEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                val ctx = requireContext()
                val padY = (6 * resources.displayMetrics.density).toInt()
                val secondary = ctx.getColorAttr(android.R.attr.textColorSecondary)
                for (profile in profiles) {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, padY, 0, padY)
                    }
                    val name = TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        text = profile.displayName()
                        textSize = 15f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val traffic = TextView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        text = trafficText(profile.tx, profile.rx)
                        textSize = 14f
                        setTextColor(secondary)
                    }
                    row.addView(name)
                    row.addView(traffic)
                    nodeList.addView(row)
                }
            }
        }
    }

    private fun showBillingDayPicker() {
        val days = (1..28).map { getString(R.string.usage_billing_day_n, it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.usage_billing_day)
            .setSingleChoiceItems(days, DataStore.trafficBillingDay - 1) { dialog, which ->
                DataStore.trafficBillingDay = which + 1
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
