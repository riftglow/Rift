package light.rift.ui

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import light.rift.core.utils.Socks5Tunnel
import light.rift.ktx.readableMessage
import java.net.URL

/**
 * 流媒体/AI/社交解锁检测（0.2.29 -26，第二批）：
 * OwnBox 的做法是 36 个服务、5 类、每服务手写分支（Netflix 两探针、
 * YouTube premium countryCode 正则、Disney+ HEAD 头……），表会随网站改版而烂，
 * 要长期维护。Rift 只做小表（11 个），且只判“能不能通”——对这些服务来说，
 * 通不通就是解锁问题本身（它们按出口地区直接封或直接放行）。
 * Netflix、Disney+ 不在表里：它们的解锁要查目录/片库（不是通不通），
 * 简单 GET 会误报“可用”，不列比列错好。
 * 探测走手动 SOCKS5 隧道（Socks5Tunnel）：-26 用的 OkHttp + java.net.socks.*
 * 系统属性在安卓上根本不起作用（SocksSocketImpl 不读这俩属性，拿 user.name
 * 加空密码去认证，全被服务端拒绝），11 个服务全报 "SOCKS : authentication failed"。
 * 这里直接发握手、账号密码、CONNECT，账号密码与配置里同一份。
 */
object StreamingUnlock {
    data class Service(val name: String, val url: String)
    enum class State { AVAILABLE, RESTRICTED, UNAVAILABLE }
    data class Result(val service: Service, val state: State, val latencyMs: Long, val detail: String)

    val SERVICES = listOf(
        // AI
        Service("ChatGPT", "https://chatgpt.com/"),
        Service("Gemini", "https://gemini.google.com/"),
        Service("Claude", "https://claude.ai/"),
        // 流媒体
        Service("YouTube", "https://www.youtube.com/"),
        Service("Spotify", "https://open.spotify.com/"),
        Service("Bilibili", "https://www.bilibili.com/"),
        // 社交/开发
        Service("X", "https://x.com/"),
        Service("GitHub", "https://github.com/"),
        Service("Reddit", "https://www.reddit.com/"),
        Service("Telegram", "https://telegram.org/"),
        Service("Wikipedia", "https://www.wikipedia.org/"),
    )

    /** 并行探全部服务；返回顺序与 SERVICES 一致。 */
    suspend fun checkAll(): List<Result> {
        return coroutineScope {
            SERVICES.map { service ->
                async { probe(service) }
            }.awaitAll()
        }
    }

    /** 单个服务：隧道 + TLS + GET，只读状态行。 */
    private fun probe(service: Service): Result {
        val start = SystemClock.elapsedRealtime()
        return try {
            // GET（不用 HEAD：有些站对 HEAD 回 405）；只读响应头就关掉，不下载整页。
            val url = URL(service.url)
            val result = Socks5Tunnel.httpsGet(url.host, url.path.ifBlank { "/" }, 4000)
            val ms = SystemClock.elapsedRealtime() - start
            when (result.statusCode) {
                in 200..399 -> Result(service, State.AVAILABLE, ms, "HTTP ${result.statusCode}")
                // 403/451 往往是地区封锁（不是连不上）；429 是被限流，也算受限。
                403, 429, 451 -> Result(service, State.RESTRICTED, ms, "HTTP ${result.statusCode}")
                else -> Result(service, State.UNAVAILABLE, ms, "HTTP ${result.statusCode}")
            }
        } catch (e: Exception) {
            Result(service, State.UNAVAILABLE, SystemClock.elapsedRealtime() - start, e.readableMessage)
        }
    }
}
