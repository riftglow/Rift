package light.rift.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import light.rift.R

/**
 * 设置子页卡片分组装饰（2026-11-15）：
 * 把同一 PreferenceCategory 下的偏好画进一张白圆角卡片，组内行之间画 1dp 分隔线，
 * 卡片左右留 16dp 边距、卡片之间留 12dp 间距；分组标题（PreferenceCategory）本身
 * 不进卡片，只作卡片上方的分组小标题。
 * 没有 PreferenceCategory 的子页（Hysteria2/AnyTLS/入站）全部偏好视为一组、共一张卡。
 * 只负责外观，不改偏好逻辑；画坏了也不影响设置功能。
 *
 * 圆角与 App 内其他卡片一致取 20dp（抽屉卡片同款），不用设计稿的 16dp。
 */
class SettingsCardItemDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density

    private val cardRadius = 20f * density
    private val marginHorizontal = (16f * density).toInt()
    private val spacingBetweenCards = (12f * density).toInt()
    private val gapHeaderToCard = (8f * density).toInt()
    private val dividerInset = (16f * density).toInt()
    private val dividerThickness = (1f * density).coerceAtLeast(1f)

    // 卡片改用 MaterialShapeDrawable（2026-11-15）：这是备份页 MaterialCardView 内部用的
    // 同一款 drawable，白底＋1dp 内嵌淡描边、0 阴影，与备份页卡片逐像素同款渲染。
    // 之前自己用 Paint 画 Path（居中描边/无描边/内嵌描边三版）底边都还显线，换成与备份页
    // 完全相同的渲染路径，不再留差别。分隔线仍用 Paint 画。
    private val cardDrawable = MaterialShapeDrawable().apply {
        fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_rift_card))
        strokeWidth = (1f * density).coerceAtLeast(1f)
        strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_rift_card_stroke))
        elevation = 0f
        // 阴影显式关掉（SHADOW_COMPAT_MODE_NEVER），避免直接画时带上阴影。
        // 注意：2026-11-15 像素取样证明阴影不是那条线的成因——#157 关阴影后暗带 206 还在；
        // 真正成因是描边外半（见 onDraw 里 canvas.clipRect 的注释），这里只作保险。
        setShadowCompatibilityMode(MaterialShapeDrawable.SHADOW_COMPAT_MODE_NEVER)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 分隔线复用卡片描边色（#1F000000，极淡），与设计稿 #E6E6E6 接近，不另加颜色。
        color = ContextCompat.getColor(context, R.color.color_rift_card_stroke)
        style = Paint.Style.FILL
    }

    private fun dp(value: Float): Float = value * density

    private fun preferenceAt(parent: RecyclerView, position: Int): Preference? {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return null
        if (position < 0 || position >= adapter.itemCount) return null
        return runCatching { adapter.getItem(position) }.getOrNull()
    }

    private fun isCategory(preference: Preference?): Boolean = preference is PreferenceCategory

    /** 同组 = 同一个父 PreferenceGroup（同一 Category，或同为根 PreferenceScreen 的直接子项）。 */
    private fun sameGroup(a: Preference?, b: Preference?): Boolean {
        if (a == null || b == null) return false
        return a.parent === b.parent
    }

    private fun isFirstInGroup(parent: RecyclerView, position: Int): Boolean {
        val pref = preferenceAt(parent, position) ?: return false
        if (isCategory(pref)) return false
        val prev = preferenceAt(parent, position - 1)
        return prev == null || isCategory(prev) || !sameGroup(prev, pref)
    }

    private fun isLastInGroup(parent: RecyclerView, position: Int): Boolean {
        val pref = preferenceAt(parent, position) ?: return false
        if (isCategory(pref)) return false
        val next = preferenceAt(parent, position + 1)
        return next == null || isCategory(next) || !sameGroup(pref, next)
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val pref = preferenceAt(parent, position) ?: return
        if (isCategory(pref)) {
            // 分组标题：与卡片左对齐；上方留间距（首项 12dp、其余由上一张卡的底间距提供，
            // 故只在首项加），下方与卡片留 8dp。
            outRect.set(
                marginHorizontal,
                if (position == 0) spacingBetweenCards else 0,
                marginHorizontal,
                gapHeaderToCard,
            )
            return
        }
        // 偏好行：左右 16dp（卡片边距）；组首行若无标题在上（单卡页首行）顶留 12dp，
        // 组末行底留 12dp（与下一张卡/标题的间距）。
        val first = isFirstInGroup(parent, position)
        val last = isLastInGroup(parent, position)
        val top = if (first && position == 0) spacingBetweenCards else 0
        val bottom = if (last) spacingBetweenCards else 0
        outRect.set(marginHorizontal, top, marginHorizontal, bottom)
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // 按父组聚类可见偏好行，逐组画卡片与组内分隔线。
        val groups = LinkedHashMap<Any, MutableList<View>>()
        val groupOrder = ArrayList<Any>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val pref = preferenceAt(parent, position) ?: continue
            if (isCategory(pref)) continue
            val key: Any = pref.parent ?: pref
            if (!groups.containsKey(key)) {
                groups[key] = ArrayList()
                groupOrder.add(key)
            }
            groups[key]!!.add(child)
        }
        for (key in groupOrder) {
            val views = groups[key] ?: continue
            if (views.isEmpty()) continue
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            var hasFirst = false
            var hasLast = false
            for (v in views) {
                val position = parent.getChildAdapterPosition(v)
                if (isFirstInGroup(parent, position)) hasFirst = true
                if (isLastInGroup(parent, position)) hasLast = true
                if (v.top < top) top = v.top
                if (v.bottom > bottom) bottom = v.bottom
            }
            val left = (parent.paddingLeft + marginHorizontal).toFloat()
            val right = (parent.width - parent.paddingRight - marginHorizontal).toFloat()
            // 组首/末行被滚出可视区时，卡片上下沿伸到列表边缘、对应角不圆，避免半张卡带圆角。
            val topY = if (hasFirst) top.toFloat() else parent.paddingTop.toFloat()
            val bottomY = if (hasLast) bottom.toFloat() else (parent.height - parent.paddingBottom).toFloat()
            if (bottomY <= topY || right <= left) continue
            // 组首/末行被滚出可视区时，对应角不圆（与备份页卡片在列表边缘的形态一致）。
            val topRadius = if (hasFirst) cardRadius else 0f
            val bottomRadius = if (hasLast) cardRadius else 0f
            cardDrawable.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCorner(CornerFamily.ROUNDED, topRadius)
                .setTopRightCorner(CornerFamily.ROUNDED, topRadius)
                .setBottomLeftCorner(CornerFamily.ROUNDED, bottomRadius)
                .setBottomRightCorner(CornerFamily.ROUNDED, bottomRadius)
                .build()
            cardDrawable.setBounds(
                (parent.paddingLeft + marginHorizontal),
                topY.toInt(),
                (parent.width - parent.paddingRight - marginHorizontal),
                bottomY.toInt(),
            )
            // 关键：MaterialShapeDrawable 的描边是沿路径居中画的，一半在卡内、一半会伸到卡外。
            // 备份页 MaterialCardView 会把背景 drawable 裁到 View 边界内，卡外那半描边看不见；
            // ItemDecoration 直接画在 RecyclerView 画布上不裁，卡外那半描边（#1F000000）就落在
            // 暖底上，像素取样正好是底色 235 叠 12% 黑 = 206，就是用户说的那条线。
            // 这里先把画布裁到卡片矩形再画，卡外那半描边被裁掉，与备份页同款。
            // （2026-11-15 像素取样确认：#157 关阴影后暗带 206 还在，阴影不是原因，是描边外半。）
            canvas.save()
            canvas.clipRect(left, topY, right, bottomY)
            cardDrawable.draw(canvas)
            canvas.restore()
            // 组内分隔线：相邻两可见行之间画一条，左右各缩进 16dp。
            val sorted = views.sortedBy { parent.getChildAdapterPosition(it) }
            for (j in 0 until sorted.size - 1) {
                val upper = sorted[j]
                val lower = sorted[j + 1]
                val upperPos = parent.getChildAdapterPosition(upper)
                val lowerPos = parent.getChildAdapterPosition(lower)
                if (lowerPos != upperPos + 1) continue
                val y = upper.bottom.toFloat()
                if (y < topY || y > bottomY) continue
                canvas.drawRect(
                    left + dividerInset,
                    y - dividerThickness / 2f,
                    right - dividerInset,
                    y + dividerThickness / 2f,
                    dividerPaint,
                )
            }
        }
    }
}
