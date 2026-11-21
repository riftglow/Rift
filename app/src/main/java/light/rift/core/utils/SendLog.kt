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
        Regex("(vmess|vless|trojan|ss|trojan-go|naive|mieru|hysteria2?|tuic|anytls|shadowtls)://\\S+")
    private val httpUrlRegex = Regex("https?://[^\\s\"'<>]+")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6Regex = Regex("\\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\b")
    private val credentialRegex =
        Regex("(?i)(password|passwd|pwd|token|secret)\\s*[:=]\\s*[^\\s,;\"']+")

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
        redacted = credentialRegex.replace(redacted, "$1=***")
        return redacted
    }

    private fun redactHttpUrl(url: String): String {
        val beforeQuery = url.substringBefore('?')
        val masked = if (beforeQuery != url) "$beforeQuery?***" else url
        val schemeEnd = masked.indexOf("://")
        if (schemeEnd < 0) return masked
        val hostEnd = masked.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return masked
        val path = masked.substring(hostEnd + 1)
        if (!path.contains('/') && !path.contains('.') && path.length >= 20) {
            return masked.substring(0, hostEnd + 1) + "***"
        }
        return masked
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
    // 出口脱敏（0.2.29 -13）：分享与本地导出都走这里；报告头是版本与设备信息，不用脱敏。
    fun writeFullLog(out: OutputStream) {
        out.write(REDACT_NOTICE.toByteArray())
        out.write(CrashHandler.buildReportHeader().toByteArray())
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
            out.write(("Export logcat error: " + CrashHandler.formatThrowable(e)).toByteArray())
        }
        out.write("\n".toByteArray())
        // -46：导出 rift.log 改与日志页相同的有界读（读最近 1MB）。日志页
        // getRiftLog(50KB) 能读出内容，导出 getRiftLog(0) 三次都空，改成有界读
        // 走同一条被验证过的路；1MB 足够覆盖过夜诊断，又不超过文件 2MB 上限。
        // 读空时写一行诊断（磁盘上的文件大小），区分“文件本来就空”和“读失败”。
        val riftBytes = getRiftLog(1024 * 1024)
        val riftText = if (riftBytes.isEmpty()) {
            val f = File(RiftApp.application.cacheDir, "rift.log")
            val size = if (f.exists()) f.length() else -1L
            "[rift.log read empty; file size=${size} bytes]\n"
        } else {
            redactLog(riftBytes.toString(Charsets.UTF_8))
        }
        out.write(riftText.toByteArray())
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
    fun getRiftLog(max: Long): ByteArray {
        return try {
            val file = File(
                RiftApp.application.cacheDir,
                "rift.log"
            )
            val len = file.length()
            FileInputStream(file).use { stream ->
                // max <= 0 = 读整个文件；0 < max < len = 只读末尾 max 字节。
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
