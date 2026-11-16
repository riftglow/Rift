package light.rift.ui

import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import light.rift.database.DataStore
import light.rift.ktx.readableMessage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 流媒体/AI/社交解锁检测（0.2.29 -26，第二批）：
 * OwnBox 的做法是 36 个服务、5 类、每服务手写分支（Netflix 两探针、
 * YouTube premium countryCode 正则、Disney+ HEAD 头……），表会随网站改版而烂，
 * 要长期维护。Rift 只做小表（11 个），且只判“能不能通”——对这些服务来说，
 * 通不通就是解锁问题本身（它们按出口地区直接封或直接放行）。
 * Netflix、Disney+ 不在表里：它们的解锁要查目录/片库（不是通不通），
 * 简单 GET 会误报“可用”，不列比列错好。
 * 探测走 OkHttp + SOCKS（连着 VPN 时），认证走 java.net.socks.* 系统属性
 * （与 NetworkFragment 的 URL 检查同一做法）。
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

    private fun newClient(): OkHttpClient {
        val connected = DataStore.serviceState.connected
        return OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .apply {
                if (connected) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
                    if (DataStore.localProxyAuthEnabled) {
                        System.setProperty("java.net.socks.username", DataStore.mixedInboundUser)
                        System.setProperty("java.net.socks.password", DataStore.mixedInboundPass)
                    } else {
                        System.clearProperty("java.net.socks.username")
                        System.clearProperty("java.net.socks.password")
                    }
                }
            }
            .build()
    }

    /** 并行探全部服务；返回顺序与 SERVICES 一致。 */
    suspend fun checkAll(): List<Result> {
        val client = newClient()
        return try {
            coroutineScope {
                SERVICES.map { service ->
                    async { probe(client, service) }
                }.awaitAll()
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun probe(client: OkHttpClient, service: Service): Result {
        val start = SystemClock.elapsedRealtime()
        return try {
            // GET（不用 HEAD：有些站对 HEAD 回 405）；只读响应头就关掉，不下载整页。
            val request = Request.Builder().url(service.url).get().build()
            client.newCall(request).execute().use { response ->
                val ms = SystemClock.elapsedRealtime() - start
                when (response.code) {
                    in 200..399 -> Result(service, State.AVAILABLE, ms, "HTTP ${response.code}")
                    // 403/451 往往是地区封锁（不是连不上）；429 是被限流，也算受限。
                    403, 429, 451 -> Result(service, State.RESTRICTED, ms, "HTTP ${response.code}")
                    else -> Result(service, State.UNAVAILABLE, ms, "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Result(service, State.UNAVAILABLE, SystemClock.elapsedRealtime() - start, e.readableMessage)
        }
    }
}
