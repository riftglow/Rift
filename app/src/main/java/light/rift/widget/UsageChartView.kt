package light.rift.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import light.rift.R
import light.rift.ktx.getColorAttr

/**
 * 最近 30 天流量柱状图（2026-11-12，0.2.21）：用量页用。
 * 一天一根竖条，高度按当天总量占 30 天最大值的比例；当天用琥珀色，
 * 其他天用次要文字色淡化。没有数据时只画一条基线。
 * 主题安全：颜色全部取主题/资源，不写死浅色。
 */
class UsageChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var days: List<Pair<String, Long>> = emptyList()

    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.color_rift_accent)
        style = Paint.Style.FILL
    }

    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColorAttr(android.R.attr.textColorSecondary)
        alpha = 90
        style = Paint.Style.FILL
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColorAttr(android.R.attr.textColorSecondary)
        alpha = 60
        style = Paint.Style.FILL
    }

    private val barRect = RectF()

    /** 30 天数据：(yyyy-MM-dd, 当天上行+下行字节数），按日期升序。 */
    fun setData(days: List<Pair<String, Long>>) {
        this.days = days
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val bottom = height.toFloat()
        canvas.drawRect(0f, bottom - 1f, width.toFloat(), bottom, baselinePaint)
        if (days.isEmpty()) return
        val max = days.maxOf { it.second }.coerceAtLeast(1L)
        val gap = 2f * resources.displayMetrics.density
        val barWidth = (width - gap * (days.size - 1)) / days.size
        val radius = 2f * resources.displayMetrics.density
        days.forEachIndexed { index, (_, total) ->
            val left = index * (barWidth + gap)
            // 全 0 也给 2dp 高，看得出“有这一天”；最高不超过 90% 高。
            val barHeight = if (total <= 0L) 2f * resources.displayMetrics.density
            else (total.toFloat() / max) * (height * 0.9f)
            barRect.set(left, bottom - barHeight, left + barWidth, bottom)
            // 最后一条是今天。
            canvas.drawRoundRect(barRect, radius, radius, if (index == days.lastIndex) todayPaint else dayPaint)
        }
    }
}
