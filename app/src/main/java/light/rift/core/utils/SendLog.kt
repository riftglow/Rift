package light.rift.core.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import light.rift.BuildConfig
import light.rift.R
import light.rift.RiftApp
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

object SendLog {
    // 脱敏提示：导出的日志已自动隐藏敏感信息，应用内查看的原始日志不动。
    private const val REDACT_NOTICE = "注：本日志已自动隐藏 IP、节点地址、UUID、订阅链接等敏感信息。\n"

    private val uuidRegex =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val proxyLinkRegex =
        Regex("(vmess|vless|trojan|ssr|ss|trojan-go|naive|mieru|hysteria2?|hy2|tuic|anytls|shadowtls|socks5?|socks4a?|sn|clash)://\\S+")
    private val httpUrlRegex = Regex("https?://[^\\s\"'<>]+")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6Regex = Regex("\\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\b")
    private val credentialRegex =
        Regex("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+")
    // JSON 形式凭证："password": "xxx" / "secret": "..." —— 上面那条
    // 要求 key 后直接跟 : 或 =，撞上 key 与冒号之间的引号就匹配不上，
    // 而 rift.log 里的 [ProxyInstance] 配置转储正是 JSON 形式，Hysteria2
    // 密码与 Clash secret 会从这里漏出去。先于 credentialRegex 跑。
    private val jsonCredentialRegex =
        Regex("(?i)(\"(?:password|passwd|pwd|token|secret|private_?key)\"\\s*:\\s*\")[^\"]*(\")")

    // 日志脱敏：掩盖公网 IP、UUID、节点链接与带凭证的 URL；内网 / Fake-IP 段与回环保留（排障要看 DNS 走了哪段）。
    // 保留时间戳、级别、错误信息、协议名与出口标签，脱敏后的日志照样能定位问题。
    internal fun redactLog(text: String): String {
        var redacted = text
        // 代理协议链接整条掩盖，只留协议名（链接里常带 UUID 与服务器地址）
        redacted = proxyLinkRegex.replace(redacted) { "${it.groupValues[1]}://***" }
        // http(s) 链接：查询串一律掩盖，单段长令牌路径（如订阅链接）整段掩盖，短路径保留
        redacted = httpUrlRegex.replace(redacted) { redactHttpUrl(it.value) }
        redacted = uuidRegex.replace(redacted, "********-****-****-****-************")
        redacted = ipv4Regex.replace(redacted) { match ->
            if (isPrivateOrReservedIpv4(match.value)) match.value else "***.***.***.***"
        }
        redacted = ipv6Regex.replace(redacted) { match ->
            val ip = match.value
            // 时分秒（只有 2 个冒号、无十六进制字母、无 ::）不是 IPv6，不动
            if (!ip.contains("::") && ip.count { it == ':' } < 4 &&
                ip.none { it in 'a'..'f' || it in 'A'..'F' }
            ) {
                ip
            } else if (ip == "::1" || ip.startsWith("fe80:") || ip.startsWith("fc") ||
                ip.startsWith("fd")
            ) {
                ip
            } else {
                "***"
            }
        }
        redacted = jsonCredentialRegex.replace(redacted, "$1***$2")
        redacted = credentialRegex.replace(redacted, "$1=***")
        return redacted
    }

    private fun redactHttpUrl(url: String): String {
        // 查询串一律掩盖；路径段一律掩盖，只留 host。短路径 token（如订阅
        // 链接 https://host/sub/AbC123）只看“单段、无点、够长”会漏网，
        // 路径本身又不是排障必需的（host 已够定位），索性全掩。
        val beforeQuery = url.substringBefore('?')
        val masked = if (beforeQuery != url) "$beforeQuery?***" else url
        val schemeEnd = masked.indexOf("://")
        if (schemeEnd < 0) return masked
        val hostEnd = masked.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return masked
        return masked.substring(0, hostEnd + 1) + "***"
    }

    private fun isPrivateOrReservedIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return true
        val a = parts[0].toIntOrNull() ?: return true
        val b = parts[1].toIntOrNull() ?: return true
        return a == 0 || a == 10 || a == 127 || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 198 && (b == 18 || b == 19)) ||
            (a == 169 && b == 254) || a >= 224
    }

    // Write the same full log that sendLog shares: report header + logcat + rift.log.
    // Used by both the share path and the local-export path so they never diverge.
    // 出口脱敏（0.2.29 -13）：分享与本地导出都走这里；报告头里含设置转储
    // （CrashHandler.buildReportHeader），一样要过 redactLog 做第二道防线，
    // 不能只靠报告头自己的过滤。
    // 返回 rift.log 段的结果（"rift.log: N bytes" 或 "rift.log: …失败原因"），
    // 调用方据此告诉用户核心日志到底有没有导出来。
    fun writeFullLog(out: OutputStream): String {
        out.write(REDACT_NOTICE.toByteArray())
        out.write(redactLog(CrashHandler.buildReportHeader()).toByteArray())
        out.write("Logcat: \n\n".toByteArray())
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            try {
                proc.inputStream.use {
                    out.write(redactLog(it.readBytes().toString(Charsets.UTF_8)).toByteArray())
                }
            } finally {
                try {
                    proc.waitFor()
                } catch (_: Exception) {
                }
                proc.destroy()
            }
            out.write("\n".toByteArray())
        } catch (e: IOException) {
            Logs.w(e)
            out.write(redactLog("Export logcat error: " + CrashHandler.formatThrowable(e)).toByteArray())
        }
        out.write("\n".toByteArray())
        // rift.log 段（-74 重写）：旧的 -47 兜底有个洞——读空/抛异常时写诊断行，
        // 但诊断行那次 out.write 本身再抛，就被内层 catch 静默吞掉，文件里既
        // 无内容也无诊断（-53、-65 与 2026-11-25 12:45 三份导出全是这么丢的）。
        // 现在每种结果都在文件尾留一行状态，写失败也不吞（状态照常返回，
        // 调用方在界面上明说核心日志缺失），不再出现“无声无息少一段”。
        var riftStatus = "rift.log: missing (unknown)"
        try {
            val riftBytes = getRiftLog(1024 * 1024)
            if (riftBytes.isEmpty()) {
                val size = try {
                    val f = File(RiftApp.application.cacheDir, "rift.log")
                    if (f.exists()) f.length() else -1L
                } catch (_: Throwable) {
                    -2L
                }
                riftStatus = "rift.log: read empty (file size=${size} bytes)"
            } else {
                val riftText = try {
                    redactLog(riftBytes.toString(Charsets.UTF_8))
                } catch (t: Throwable) {
                    Logs.w(t)
                    riftStatus =
                        "rift.log: redact failed (${t.javaClass.simpleName}), dropped ${riftBytes.size} raw bytes"
                    null
                }
                if (riftText != null) {
                    out.write(riftText.toByteArray())
                    out.flush()
                    riftStatus = "rift.log: ${riftBytes.size} bytes"
                }
            }
        } catch (t: Throwable) {
            Logs.w(t)
            riftStatus = "rift.log: write failed (${t.javaClass.simpleName}: ${t.message})"
        }
        // 尾行：导出永远以 rift.log 的结果收尾，缺段一眼就能看见。
        try {
            out.write(redactLog("[${riftStatus}]\n").toByteArray())
            out.flush()
        } catch (_: Throwable) {
            // 输出流本身已坏，文件里写不进去了；状态照常返回给调用方报出来。
        }
        return riftStatus
    }

    // Create full log and send
    fun sendLog(context: Context, title: String) {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

        FileOutputStream(logFile).use { writeFullLog(it) }

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    // Get log bytes from rift.log. Missing/unreadable log is an empty
    // result, never a stack trace disguised as log content.
    // 已知局限：导出读文件时 Go 侧可能正在截断（满 2MB 时保留后半段），
    // 读到的可能是截断前后的混合内容（缺几行或多几行），不影响排障；
    // 截断窗口极小，不上跨进程锁。
    fun getRiftLog(max: Long): ByteArray {
        return try {
            val file = File(
                RiftApp.application.cacheDir,
                "rift.log"
            )
            val len = file.length()
            FileInputStream(file).use { stream ->
                // max <= 0 = 读整个文件（保留全读兼容，当前调用方只传正数）；
                // 0 < max < len = 只读末尾 max 字节。
                // 用显式比较，不用 `max in 1 until len` 的区间写法，避免歧义。
                if (max > 0 && max < len) {
                    var toSkip = len - max
                    while (toSkip > 0) {
                        val skipped = stream.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }
                stream.readBytes()
            }
        } catch (e: Exception) {
            Logs.w(e)
            ByteArray(0)
        }
    }
}
