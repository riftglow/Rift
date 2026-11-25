package light.rift.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import light.rift.R
import light.rift.databinding.LayoutLogcatBinding
import light.rift.ktx.*
import light.rift.widget.ListListener
import libcore.Libcore
import light.rift.core.utils.SendLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    // Export to local file via Storage Access Framework: no storage permission
    // needed, the user picks the folder and filename in the system picker.
    private val exportLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            runOnDefaultDispatcher {
                try {
                    val resolver = context?.contentResolver
                        ?: throw IllegalStateException("fragment detached")
                    var riftStatus = ""
                    resolver.openOutputStream(uri)?.use {
                        riftStatus = SendLog.writeFullLog(it)
                    } ?: throw IllegalStateException("cannot open output")
                    onMainDispatcher {
                        // 核心日志缺失要明说，不能只报“已导出”——以前就是这么
                        // 把缺段的导出当成功的（-74：writeFullLog 返回状态）。
                        if (isAdded) {
                            // 成功态形如 "rift.log: 123456 bytes"；失败态里
                            // "dropped N raw bytes" 也含 bytes，不能只含就判成功。
                            if (riftStatus.matches(Regex("rift\\.log: \\d+ bytes"))) {
                                snackbar(R.string.log_exported_redacted).show()
                            } else {
                                snackbar(riftStatus).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    onMainDispatcher {
                        try {
                            if (isAdded) snackbar(e.readableMessage).show()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        // 级别筛选 chips（2026-11-16，0.2.29）：点一下筛一行，选中态琥珀描边。
        val chips = listOf(
            binding.chipAll to null,
            binding.chipInfo to LogLevel.INFO,
            binding.chipWarn to LogLevel.WARN,
            binding.chipError to LogLevel.ERROR,
            binding.chipDebug to LogLevel.DEBUG,
        )
        for ((chip, level) in chips) {
            chip.setOnClickListener {
                logFilter = level
                updateFilterChips()
                reloadSession()
            }
        }
        updateFilterChips()

        reloadSession()
    }

    // ANSI 转义码（sing-box 日志自带终端着色，如 [36mINFO [0m）：TextView 里直接显示就是乱码。
    // 只去掉 SGR 颜色码（ESC [ ... m），不改其他内容。
    private val ansiRegex = Regex("\u001B\\[[0-9;]*m")

    private enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    // 当前级别筛选（null = 全部）。点 chip 切换，reloadSession 时筛行。
    private var logFilter: LogLevel? = null

    // 行首单词就是级别（去 ANSI 之后如 "INFO [4307] ..."）。
    // 旧判定 contains("INFO[") 永远匹配不上（实际是 "INFO ["，中间有空格），
    // 所以之前所有行都是灰的，ERROR 也没变红。
    private fun levelOf(line: String): LogLevel? = when {
        line.startsWith("ERROR") || line.startsWith("FATAL") || line.startsWith("PANIC") -> LogLevel.ERROR
        line.startsWith("WARN") -> LogLevel.WARN
        line.startsWith("INFO") -> LogLevel.INFO
        line.startsWith("DEBUG") || line.startsWith("TRACE") -> LogLevel.DEBUG
        else -> null
    }

    private fun colorFor(level: LogLevel?): Int {
        return ContextCompat.getColor(
            requireContext(), when (level) {
                LogLevel.INFO -> R.color.color_rift_success
                // WARN 原先和 ERROR 一样是红色，错的，改成琥珀。
                LogLevel.WARN -> R.color.color_rift_accent_dark
                LogLevel.ERROR -> R.color.color_rift_danger
                LogLevel.DEBUG, null -> android.R.color.darker_gray
            }
        )
    }

    private fun updateFilterChips() {
        val chips = listOf(
            binding.chipAll to null,
            binding.chipInfo to LogLevel.INFO,
            binding.chipWarn to LogLevel.WARN,
            binding.chipError to LogLevel.ERROR,
            binding.chipDebug to LogLevel.DEBUG,
        )
        for ((chip, level) in chips) {
            // 选中态由 chip_log_filter_bg / chip_log_filter_text 的 state_selected 画，
            // 这里只改 isSelected。
            chip.isSelected = (logFilter == level)
        }
    }

    // L-2: 50KB 日志的读文件＋逐行解析放后台线程，建好 Spannable 再回主线程上屏；
    // 解析期间 Fragment 可能已退出，回主线程先判 isAdded。
    private fun reloadSession() {
        runOnDefaultDispatcher {
            val raw = String(SendLog.getRiftLog(50 * 1024), Charsets.UTF_8)
            val text = StringBuilder()
            val spans = mutableListOf<Triple<Int, Int, ForegroundColorSpan>>()
            var lastLevel: LogLevel? = null
            for (rawLine in raw.lines()) {
                val line = ansiRegex.replace(rawLine, "")
                val ownLevel = levelOf(line)
                if (ownLevel != null) lastLevel = ownLevel
                // 无级别的行继承上一行的级别（多行堆栈跟所属条目走），过滤时才不丢。
                val effectiveLevel = ownLevel ?: lastLevel
                if (logFilter != null && effectiveLevel != logFilter) continue
                val start = text.length
                text.append(line).append('\n')
                spans.add(Triple(start, text.length - 1, ForegroundColorSpan(colorFor(ownLevel))))
            }
            val span = SpannableString(text.toString())
            for ((start, end, colorSpan) in spans) {
                span.setSpan(colorSpan, start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                binding.textview.text = span
                binding.textview.clearFocus()
                // 等 textview 完成最终 layout 再滚动到底部
                binding.textview.doOnLayout {
                    binding.scrollview.scrollTo(0, binding.textview.height)
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        Libcore.riftLogClear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = context ?: return true
                runOnDefaultDispatcher {
                    try {
                        SendLog.sendLog(context, "Rift")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            try {
                                if (isAdded) snackbar(e.readableMessage).show()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            R.id.action_export_logcat -> {
                val name = "Rift-" + SimpleDateFormat(
                    "yyyyMMdd-HHmmss", Locale.US
                ).format(Date()) + ".log"
                exportLogLauncher.launch(name)
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}