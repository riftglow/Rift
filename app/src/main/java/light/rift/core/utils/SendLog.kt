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
import java.io.ByteArrayOutputStream
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
    // -79 D-1：key 部分加 [\w-]* 后缀。原先只认 password/passwd/pwd/
    // token/secret 五个整词，SSH 的 private_key_passphrase、auth_str
    // 这类带后缀的 key 漏网——ProxyInstance 每建一次实例就把整份配
    // 置 JSON 打进日志，口令明文外泄。后缀用 [\w-]* 而不用 .*，避免
    // 吞掉后面的冒号；替换仍是 "$1=***"（$1 只含 key 的词干）。
    private val credentialRegex =
        Regex("(?i)(password|passwd|pwd|token|secret|passphrase|auth|private)[\\w-]*\\s*[:=]\\s*[^\\s,;\"']+")
    // JSON 形式凭证："password": "xxx" / "secret": "..." —— 上面那条
    // 要求 key 后直接跟 : 或 =，撞上 key 与冒号之间的引号就匹配不上，
    // 而 rift.log 里的 [ProxyInstance] 配置转储正是 JSON 形式，Hysteria2
    // 密码与 Clash secret 会从这里漏出去。先于 credentialRegex 跑。
    // -79 D-1：key 部分加 [^"]* 后缀，private_key_passphrase（private_?key
    // + "_passphrase"）、auth_str（auth + "_str"）都进掩盖；passphrase
    // 单独出现时也掩盖。$1 仍是 `"key": "` 整体，替换 "$1***$2" 不变。
    private val jsonCredentialRegex =
        Regex("(?i)(\"(?:password|passwd|pwd|token|secret|private_?key|passphrase|auth)[^\"]*\"\\s*:\\s*\")[^\"]*(\")")

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

    // -77：大块内容分段写。00:57 那份 -76 导出停在 logcat 之后、
    // 核心日志段之前：1MB 整块 out.write 到 SAF 输出流时坏了，
    // 后面的尾行也写不进去。改 64KB 一段、每段 flush；同时记一
    // 行诊断，万一再断，下次导出的 logcat 里能看到断在哪一段。
    private fun writeChunked(out: OutputStream, bytes: ByteArray) {
        val chunk = 64 * 1024
        var off = 0
        while (off < bytes.size) {
            val len = minOf(chunk, bytes.size - off)
            out.write(bytes, off, len)
            out.flush()
            off += len
        }
    }

    // Write the same full log that sendLog shares: report header + logcat + rift.log.
    // Used by both the share path and the local-export path so they never diverge.
    // 出口脱敏（0.2.29 -13）：分享与本地导出都走这里；报告头里含设置转储
    // （CrashHandler.buildReportHeader），一样要过 redactLog 做第二道防线，
    // 不能只靠报告头自己的过滤。
    // 返回 rift.log 段的结果（"rift.log: N bytes" 或 "rift.log: …失败原因"），
    // 调用方据此告诉用户核心日志到底有没有导出来。
    fun writeFullLog(out: OutputStream): String {
        // -78：导出先在内存里拼完整，再一次性拷到输出流。
        // 之前五次截断（-53/-65、-74 19:39、-76 00:57、-77 07:29/07:32）
        // 有个共同点：文件停在 logcat 之后、rift.log 段之前，一个字节都
        // 没进去，尾行也没有。-74 的尾行、-75 的 never-throws、-77 的
        // 分块写都只兜得住“抛异常”的失败；一旦 rift.log 那步卡住（正则
        // 在某段上失控、输出流在写大段时断掉），函数根本走不到写尾行的
        // 地方，文件就静默地缺段、界面也不报错。
        // 改法：① 整份导出先拼进内存 buffer，尾行一定写进 buffer 再拷，
        // 拼的过程中任何失败都变成状态行写进文件；② rift.log 按行脱敏，
        // 正则失控最多只卡在一行上；③ 最后把 buffer 分块拷到 out，拷
        // 失败单独报状态——输出流的问题再也不会伪装成缺段。
        var riftStatus = "rift.log: missing (unknown)"
        val buf = ByteArrayOutputStream()
        try {
            buf.write(REDACT_NOTICE.toByteArray())
            buf.write(redactLog(CrashHandler.buildReportHeader()).toByteArray())
            buf.write("Logcat: \n\n".toByteArray())
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
                try {
                    proc.inputStream.use {
                        val logcatBytes =
                            redactLog(it.readBytes().toString(Charsets.UTF_8)).toByteArray()
                        Logs.d("SendLog: writing logcat ${logcatBytes.size} bytes")
                        buf.write(logcatBytes)
                    }
                } finally {
                    try {
                        proc.waitFor()
                    } catch (_: Exception) {
                    }
                    proc.destroy()
                }
                buf.write("\n".toByteArray())
            } catch (e: IOException) {
                Logs.w(e)
                buf.write(redactLog("Export logcat error: " + CrashHandler.formatThrowable(e)).toByteArray())
            }
            buf.write("\n".toByteArray())
            // rift.log 段：每种结果都在文件尾留一行状态（-74），写失败要返回给
            // 调用方在界面上明说，不能只在文件里赌一把。
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
                    // 按行脱敏：整段 1MB 一次过正则，万一某一行让某个模式失控，
                    // 卡住的是整段；按行切开后最多只影响一行。各模式本来就是
                    // 行内匹配（链接、IP、UUID、凭证都不跨行），按行切不丢掩盖。
                    val riftText = try {
                        riftBytes.toString(Charsets.UTF_8).lineSequence()
                            .joinToString("\n") { redactLog(it) }
                    } catch (t: Throwable) {
                        Logs.w(t)
                        riftStatus =
                            "rift.log: redact failed (${t.javaClass.simpleName}), dropped ${riftBytes.size} raw bytes"
                        null
                    }
                    if (riftText != null) {
                        val riftOutBytes = riftText.toByteArray()
                        Logs.d("SendLog: writing rift.log ${riftBytes.size} raw bytes as ${riftOutBytes.size} redacted bytes")
                        buf.write(riftOutBytes)
                        riftStatus = "rift.log: ${riftBytes.size} bytes"
                    }
                }
            } catch (t: Throwable) {
                Logs.w(t)
                riftStatus = "rift.log: write failed (${t.javaClass.simpleName}: ${t.message})"
            }
        } catch (t: Throwable) {
            // 拼 buffer 时抛了：状态照常进文件、界面上明说，不再无声无息。
            Logs.w(t)
            riftStatus = "export failed: ${t.javaClass.simpleName}: ${t.message}"
        }
        // 尾行：一定写进 buffer（哪怕上面哪步失败了），缺段一眼就能看见。
        try {
            buf.write(redactLog("[${riftStatus}]\n").toByteArray())
            buf.flush()
        } catch (_: Throwable) {
        }
        // 最后一步才碰输出流：把拼好的内容分块拷过去；输出流在这里断，
        // 状态照常返回给调用方报出来，不再伪装成缺段。
        return try {
            Logs.d("SendLog: copying ${buf.size()} bytes to output")
            writeChunked(out, buf.toByteArray())
            out.flush()
            riftStatus
        } catch (t: Throwable) {
            Logs.w(t)
            "export copy failed (${t.javaClass.simpleName}: ${t.message}); $riftStatus"
        }
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
