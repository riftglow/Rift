package light.rift.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import light.rift.R
import light.rift.bg.BaseService
import light.rift.database.DataStore
import light.rift.database.ProfileManager
import light.rift.ktx.*
import light.rift.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底栏缺口背景（2026-11-13，0.2.23 -10）：矩形减去顶边中心一个圆，
 * 顶边留一个半圆豁口，圆开关嵌进去。StatsBar 是 BottomAppBar，但开关
 * 不锚在栏上（断开时栏会藏，开关要一直能点），所以不用 BottomAppBar
 * 自带的 cradle（要锚定才画），直接画这个缺口背景。
 */
private class NotchedBarDrawable(
    private val fillColor: Int,
    private val notchRadiusPx: Float,
) : Drawable() {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val rectPath = Path().apply {
            addRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                Path.Direction.CW,
            )
        }
        // 圆心落在顶边上：下半圆在栏内（被减掉成豁口），上半圆在栏外（无影响）。
        val circlePath = Path().apply {
            addCircle(bounds.exactCenterX(), bounds.top.toFloat(), notchRadiusPx, Path.Direction.CW)
        }
        rectPath.op(circlePath, Path.Op.DIFFERENCE)
        path.set(rectPath)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    init {
        // 底栏缺口（2026-11-13，0.2.23 -10）：栏顶边中间留豁口，圆开关嵌进去。
        // 缺口半径 32dp（开关半径 24dp + 8dp 余量）。颜色取 color_rift_bottom_bar，
        // 昼夜自适应（换主题时 Activity 重建，背景跟着重建）。
        val notchRadiusPx = 32f * resources.displayMetrics.density
        background = NotchedBarDrawable(
            ContextCompat.getColor(context, R.color.color_rift_bottom_bar),
            notchRadiusPx,
        )
    }

    private lateinit var statusText: TextView
    private lateinit var nodeText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    // 底栏第二行：节点名 + 出口 IP（“节点名 · 🇺🇸 IP”），见 setExitIp。
    private var nodeName: String? = null
    private var exitIpText: String? = null

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }

        // 结合原版的优化：原版不动，只修半截卡住。
        // 前两版的问题：第一版停下时一律回缺口，隐藏没了；第二版停下时一律按方向
        // 藏/显，还顺手 hide/show 开关，导致每次往下滚开关都必定消失，比原版
        // 偶尔卡一下还糟。所以这一版只在栏真正停在半截时才动手：滚动结束时看
        // 栏的实际位移，位移是 0（全显）或等于栏高（全藏）就原样不动，完全不干预
        // 原版流程；只有位移在中间时，才就近归位——露出一半以上回缺口，不到一半
        // 完整藏到底。开关不单独 hide/show，继续像原版那样锚在栏上跟着栏走，
        // 不会再每次往下滚就必定消失。
        override fun onStopNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            type: Int,
        ) {
            super.onStopNestedScroll(coordinatorLayout, child, target, type)
            if (!getAllowShow()) return
            if (!child.hideOnScroll) return
            val height = child.measuredHeight
            if (height <= 0) return
            val offset = child.translationY
            // 全显或全藏：原版已经走完了，别动。
            if (offset <= 1f || offset >= height - 1f) return
            val show = offset < height / 2f
            child.post {
                if (!getAllowShow()) return@post
                if (show) slideUp(child) else slideDown(child)
            }
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        nodeText = findViewById(R.id.node)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        // 内容未变不写，避免无谓 requestLayout 带着列表重排。
        if (statusText.text.toString() != text.toString()) {
            statusText.text = text
            TooltipCompat.setTooltipText(this, text)
        }
    }

    fun setNodeName(name: String?) {
        if (!this::nodeText.isInitialized) return
        nodeName = name
        // 没有节点就没有出口 IP：断开时 setNodeName(null) 会顺带清掉它。
        if (name == null) exitIpText = null
        updateNodeLine()
    }

    /**
     * 出口 IP（2026-11-10）：底栏第二行常驻显示。2026-11-12 首页重设计：
     * “出口”两个字去掉，国旗 + IP + 属地名就够了（IP 配国旗已自明）。
     * 格式：“🇺🇸 IP · 美国”（国旗 + IP + 属地名）。
     * 属地名由国家代码经 Locale 取中文名；取不到只显示国旗 + IP。
     * null = 不显示，只显示节点名。
     */
    fun setExitIp(ip: String?, countryCode: String?) {
        if (!this::nodeText.isInitialized) return
        exitIpText = if (ip.isNullOrBlank()) {
            null
        } else {
            val flag = flagFor(countryCode)
            val country = countryName(countryCode)
            val head = if (flag.isNotBlank()) "$flag $ip" else ip
            if (country.isNotBlank()) "$head · $country" else head
        }
        updateNodeLine()
    }

    private fun countryName(countryCode: String?): String {
        if (countryCode.isNullOrBlank() || countryCode.length != 2) return ""
        return try {
            java.util.Locale("", countryCode.uppercase())
                .getDisplayCountry(java.util.Locale.CHINESE)
        } catch (_: Exception) {
            ""
        }
    }

    private fun updateNodeLine() {
        // 有出口 IP 时第二行常驻出口 IP（设计图）；没有时显示节点名。
        // 内容未变不写，避免无谓 requestLayout 带着列表重排。
        val line = exitIpText ?: (nodeName ?: "")
        if (nodeText.text.toString() != line) nodeText.text = line
    }

    /**
     * 底栏第一行右端（设计图）：“198 ms · 重测”。
     * pingMs > 0 显示当前节点延时 + 重测；未测过显示“已连接 · 重测”。
     * 只在仍处于已连接时才改，避免断开后被旧值覆盖。
     */
    fun setConnectedDelay(pingMs: Int) {
        if (!this::statusText.isInitialized) return
        if (!DataStore.serviceState.connected) return
        val retest = app.getString(R.string.connection_test_retest)
        setStatus(
            if (pingMs > 0) "$pingMs ms · $retest"
            else "${app.getText(R.string.vpn_connected)} · $retest"
        )
    }

    private fun flagFor(countryCode: String?): String {
        if (countryCode.isNullOrBlank() || countryCode.length != 2) return ""
        return countryCode.uppercase().map { c ->
            String(Character.toChars(0x1F1E6 + (c - 'A')))
        }.joinToString("")
    }

    fun changeState(state: BaseService.State, profileName: String? = null) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        // 2026-11-13 -10：底栏固定，不随滚动隐藏（缺口版先固定，隐藏以后再说）。
        // 以前连上时 hideOnScroll=true、滚动会藏栏；现在一律 false。
        hideOnScroll = false
        if (state == BaseService.State.Connected) {
            if (profileName != null) setNodeName(profileName)
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            setNodeName(null)
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        // 内容未变不写：每秒 ticker 都会调这里，0 B/s 时数字不变，
        // 新字符串 setText 会强制 requestLayout，带着列表重排 = 静置闪。
        val tx = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        if (txText.text.toString() != tx) txText.text = tx
        val rx = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
        if (rxText.text.toString() != rx) rxText.text = rx
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    // 设计图第一行：“延时 Xms · 重测”（测完仍可点重测）。
                    val retest = app.getString(R.string.connection_test_retest)
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        ) + " · $retest"
                    )
                    // 延时同步：把重测结果写回当前节点，卡片才跟着变。
                    // 否则卡片一直显示上次批量测的旧延时，与底栏新值对不上（0.2.11 就有）。
                    // 与批量测成功分支一致：status = 1、ping = 结果，再走
                    // updateProfile 写库 + 通知 adapter 单项刷新。
                    runOnDefaultDispatcher {
                        val profile = ProfileManager.getProfile(DataStore.selectedProxy)
                        if (profile != null) {
                            profile.status = 1
                            profile.ping = elapsed
                            ProfileManager.updateProfile(profile)
                        }
                    }
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    // 切换节点时核心关闭，正在跑的检查会被掐断，
                    // 报 "use of closed network connection"——不是节点不通。
                    // 这种不写失败状态、不弹提示，等新连接上来状态自然刷新。
                    // 真失败（超时/拒绝/重置等）不受影响，照常提示。
                    if (e.readableMessage.contains(
                            "use of closed network connection", ignoreCase = true
                        )
                    ) {
                        return@onMainDispatcher
                    }
                    // S-U2: a failed test must say it failed — the
                    // old code re-set "Testing…", so the bar showed
                    // "testing" forever after a failure.
                    setStatus(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    )

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
