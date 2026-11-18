package light.rift.core.utils

import light.rift.database.DataStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * 安全加固（0.2.29 -26，俄罗斯 tr0mb1r hardening 的 Rift 版，只取官方核心支持的部分）：
 * - Clash API secret：随机 32 位 hex，应用内自己带上，对用户透明。
 * - 本地代理强制认证：mixed 入口 users，用户名固定 "rift"，密码随机 16 位 hex，设置里可见。
 * - 随机端口：mixedPort 若仍是默认 2080，随机一次、固定下来（不每次变），已手动改过的端口不动。
 * 幂等：只在值缺失时生成。由 ConfigBuilder.buildConfig 在后台线程调用；恶意应用扫描不搬
 * （tr0mb1r 的名单是俄罗斯专用的，对这边没用、误报风险大）。
 */
object SecurityHardening {
    private val random = SecureRandom()

    private fun randomHex(chars: Int): String {
        val bytes = ByteArray(chars / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // 随机端口试绑：先试占一下，被占了就换一个，最多试 10 次再兜底。
    // 试绑只能排除“当时已被占用”的端口，不能保证 sing-box bind 时仍空闲，
    // 但把“固定下一个已被占用的端口、每次启动都起不来”的死局去掉了。
    private fun pickFreePort(): Int {
        repeat(10) {
            val port = 10000 + random.nextInt(50000)
            try {
                ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }
                return port
            } catch (_: IOException) {
                // 被占用，换一个再试。
            }
        }
        return 10000 + random.nextInt(50000)
    }

    fun ensureInitialized() {
        if (DataStore.clashApiSecret.isBlank()) {
            DataStore.clashApiSecret = randomHex(32)
        }
        if (DataStore.mixedInboundPass.isBlank()) {
            DataStore.mixedInboundPass = randomHex(16)
        }
        // 端口只随机一次：仍是默认 2080 才换；用户手动改过的端口不动。
        if (DataStore.mixedPort == 2080) {
            DataStore.mixedPort = pickFreePort()
        }
    }
}
