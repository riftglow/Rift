package light.rift.core.utils

import light.rift.database.DataStore
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

    fun ensureInitialized() {
        if (DataStore.clashApiSecret.isBlank()) {
            DataStore.clashApiSecret = randomHex(32)
        }
        if (DataStore.mixedInboundPass.isBlank()) {
            DataStore.mixedInboundPass = randomHex(16)
        }
        // 端口只随机一次：仍是默认 2080 才换；用户手动改过的端口不动。
        if (DataStore.mixedPort == 2080) {
            DataStore.mixedPort = 10000 + random.nextInt(50000)
        }
    }
}
