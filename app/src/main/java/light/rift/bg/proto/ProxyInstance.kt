package light.rift.bg.proto

import light.rift.BuildConfig
import light.rift.bg.BaseService
import light.rift.bg.ServiceNotification
import light.rift.database.ProxyEntity
import light.rift.ktx.Logs
import light.rift.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import light.rift.core.utils.JavaUtil

/**
 * The core instance behind a running profile: builds the config, starts
 * the sing-box core, and owns the [TrafficLooper] that samples its traffic
 * while it runs.
 */
class ProxyInstance(
    profile: ProxyEntity,
    var service: BaseService.Interface? = null,
) : BoxInstance(profile) {

    /** False for throwaway instances built only to preview a config. */
    var notTmp = true

    /** Selector group the built config landed in, for the UI to highlight. */
    var lastSelectorGroupId = -1L

    /** Title shown in the foreground notification. */
    var displayProfileName = ServiceNotification.genTitle(profile)

    /** Traffic sampler, alive only while the core runs. */
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        if (notTmp) {
            Logs.d(config.config)
            if (BuildConfig.DEBUG) {
                Logs.d(JavaUtil.gson.toJson(config.trafficMap))
            }
        }
    }

    /** Build the config without logging it — only for temporary instances. */
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        super.close()
        runBlocking {
            looper?.stop()
            looper = null
        }
    }

}
