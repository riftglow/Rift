package light.rift.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import light.rift.R
import java.util.ArrayDeque

/**
 * 实时下行速度波形（2026-11-12，0.2.21）：首页状态卡里的小折线图。
 * 自己攒最近 60 个采样点（ConfigurationFragment 每秒喂一次 rxRate），
 * 琥珀线 + 下方淡填充。采样全 0 时画贴底直线，不闪。
 * 2026-11-13（0.2.23 -4）：直线改平滑曲线，尖峰磨圆成波浪；高度由布局压到 28dp。
 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val MAX_SAMPLES = 60
    }

    private val samples = ArrayDeque<Long>(MAX_SAMPLES)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.color_rift_accent)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 每秒调一次，传下行速度（bytes/s）；只留最近 60 个点。 */
    fun addSample(bytesPerSecond: Long) {
        if (samples.size >= MAX_SAMPLES) samples.removeFirst()
        samples.addLast(bytesPerSecond.coerceAtLeast(0L))
        invalidate()
    }

    /** 用 service 记的历史（最近 60 个下行速率）做初始样本；只留最近 60 个。
     *（2026-11-12，0.2.21 -5：后台流量也有记录，进首页时种一次。） */
    fun setSamples(history: List<Long>) {
        samples.clear()
        history.takeLast(MAX_SAMPLES).forEach { samples.addLast(it.coerceAtLeast(0L)) }
        invalidate()
    }

    /** 清空全部采样点（首页“清除流量统计”用），回到空闲基线。 */
    fun clearSamples() {
        samples.clear()
        invalidate()
    }

    /** 平滑曲线（2026-11-13，0.2.23 -4）：Catmull-Rom 转三次贝塞尔，尖峰磨圆成波浪；
     * 点少于 3 个时退化成直线。调用前 path 当前点须已是 pts[0]。 */
    private fun addSmoothCurve(path: Path, pts: List<Pair<Float, Float>>) {
        if (pts.size < 3) {
            for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
            return
        }
        for (i in 0 until pts.size - 1) {
            val p0 = pts.getOrNull(i - 1) ?: pts[i]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts.getOrNull(i + 2) ?: p2
            path.cubicTo(
                p1.first + (p2.first - p0.first) / 6f,
                p1.second + (p2.second - p0.second) / 6f,
                p2.first - (p3.first - p1.first) / 6f,
                p2.second - (p3.second - p1.second) / 6f,
                p2.first, p2.second
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty() || width <= 0 || height <= 0) return
        val max = samples.maxOrNull() ?: 0L
        val bottom = height.toFloat()
        // 基线留 2dp：全 0 时线画在 bottom-2dp 处，看得见，不贴底被裁；
        // 有流量时按最大值缩放，顶部留 10% 不顶到上沿。
        val baselineY = bottom - 2f * resources.displayMetrics.density
        val values = samples.toList()
        val stepX = if (values.size > 1) width.toFloat() / (MAX_SAMPLES - 1) else 0f
        // 画布右对齐：点不够 60 个时从右边往左排，线贴着右沿走。
        val startX = width - stepX * (values.size - 1)
        val pts = values.mapIndexed { index, value ->
            val x = startX + index * stepX
            val y = if (max <= 0L) baselineY else bottom - (value.toFloat() / max) * (height * 0.9f)
            x to y
        }
        val line = Path().apply {
            moveTo(pts.first().first, pts.first().second)
            addSmoothCurve(this, pts)
        }
        val fill = Path().apply {
            moveTo(pts.first().first, bottom)
            lineTo(pts.first().first, pts.first().second)
            addSmoothCurve(this, pts)
            lineTo(pts.last().first, bottom)
            close()
        }
        // 渐变填充（2026-11-12 首页重设计 -4）：线下是琥珀色渐变，上面实、下面淡出，
        // 有层次感，不像平色填充那么死板。渐变按视图高度来，上面琥珀 alpha 约 110，
        // 下面完全透明。shader 每帧重建（每秒才画一次），省得管尺寸变化。
        val accent = context.getColor(R.color.color_rift_accent)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }
}
