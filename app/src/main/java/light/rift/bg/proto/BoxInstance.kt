package light.rift.bg.proto

import light.rift.bg.AbstractInstance
import light.rift.bg.GuardedProcessPool
import light.rift.database.ProxyEntity
import light.rift.fmt.ConfigBuildResult
import light.rift.fmt.buildConfig
import light.rift.ktx.*
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import light.rift.core.net.LocalResolverImpl

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        loadConfig()
    }

    override fun launch() {
        for ((chain) in config.externalIndex) {
            for ((port, _) in chain.entries) {
                if (externalInstances.containsKey(port)) {
                    externalInstances[port]!!.launch()
                }
            }
        }

        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::box.isInitialized) {
            box.close()
        }
    }

}
