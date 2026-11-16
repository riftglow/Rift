package light.rift.bg.proto

import light.rift.BuildConfig
import light.rift.RiftApp
import light.rift.bg.GuardedProcessPool
import light.rift.database.ProxyEntity
import light.rift.fmt.buildConfig
import light.rift.ktx.Logs
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.ktx.tryResume
import light.rift.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import light.rift.core.net.LocalResolverImpl
import java.io.File
import java.util.UUID
import kotlin.coroutines.suspendCoroutine

/**
 * A throwaway core used for one latency probe: it builds a test-only
 * config for [profile], starts the core, runs [Libcore.urlTest] against
 * [link], and tears everything down. Nothing here touches the running
 * service instance.
 */
class TestInstance(
    profile: ProxyEntity,
    val link: String,
    private val timeout: Int,
) : BoxInstance(profile) {

    // 每个测速临时核心用独立的缓存文件（0.2.29 -14）：
    // 默认 cache.db 会被主实例的 bbolt 锁占住，临时核心启动就超时。
    private val testCachePath = "cache-test-${UUID.randomUUID()}.db"

    /** Run the probe and return the measured latency in milliseconds. */
    suspend fun doTest(): Int = suspendCoroutine { continuation ->
        processes = GuardedProcessPool { failure ->
            Logs.w(failure)
            continuation.tryResumeWithException(failure)
        }
        runOnDefaultDispatcher {
            use {
                try {
                    init()
                    launch()
                    if (processes.processCount > 0) {
                        // Give plugin processes a moment to come up.
                        delay(500)
                    }
                    continuation.tryResume(Libcore.urlTest(box, link, timeout))
                } catch (e: Exception) {
                    continuation.tryResumeWithException(e)
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true, testCachePath = testCachePath)
    }

    override fun close() {
        super.close()
        // 核心已关、DB 句柄已释放，再删临时缓存文件。
        runCatching {
            File(RiftApp.application.noBackupFilesDir, testCachePath).delete()
        }
    }

    override suspend fun loadConfig() {
        // Deliberately no destroyAllJsi call here.
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
