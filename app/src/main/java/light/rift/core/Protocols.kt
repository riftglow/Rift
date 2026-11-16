package light.rift.core

import android.content.Context
import light.rift.R
import light.rift.fmt.AbstractBean
import light.rift.ktx.app
import light.rift.ktx.getColorAttr
import light.rift.core.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            if (bean is ConfigBean) {
                return bean.config
            }
            return bean.serverAddress + bean.serverPort + type
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return getColorAttr(R.attr.accentOrTextSecondary)
    }

    // Test

    // 把启动/连接失败的原始报错映射成一句能指导下一步的短文案。
    // 命中不了的保持原样返回（调用方靠“变没变”判断是否兜底显示“不可用”），
    // 绝不把异常堆栈或整段 Go 报错原样贴给用户。
    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout)
            }

            msgL.contains("tls") || msgL.contains("handshake") ||
                msgL.contains("certificate") || msgL.contains("ssl") ||
                msgL.contains("alert") -> {
                app.getString(R.string.connection_test_tls)
            }

            msgL.contains("plugin") -> {
                app.getString(R.string.connection_test_plugin)
            }

            msgL.contains("cipher") || msgL.contains("unsupported") ||
                msgL.contains("unknown type") || msgL.contains("unknown method") ||
                msgL.contains("not supported") -> {
                app.getString(R.string.connection_test_unsupported)
            }

            msgL.contains("empty") || msgL.contains("missing") ||
                msgL.contains("required") || msgL.contains("invalid address") ||
                msgL.contains("invalid port") -> {
                app.getString(R.string.connection_test_config)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            msgL.contains("unreachable") || msgL.contains("no route") ||
                msgL.contains("reset") || msgL.contains("eof") ||
                msgL.contains("network is down") ||
                msgL.contains("connection aborted") -> {
                app.getString(R.string.connection_test_network)
            }

            else -> msg
        }
    }

}