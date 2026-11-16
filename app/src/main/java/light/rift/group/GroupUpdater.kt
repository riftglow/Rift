package light.rift.group

import light.rift.*
import light.rift.database.DataStore
import light.rift.database.GroupManager
import light.rift.database.ProxyGroup
import light.rift.database.SubscriptionBean
import light.rift.fmt.AbstractBean
import light.rift.fmt.http.HttpBean
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.fmt.trojan.TrojanBean
import light.rift.fmt.v2ray.StandardV2RayBean
import light.rift.fmt.v2ray.isTLS
import light.rift.ktx.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER

        // S-B5: resolve inside this update's coroutine scope (not
        // GlobalScope), at most 5 concurrent lookups, each with its
        // own 10s deadline — a hung DNS server can no longer stall
        // the update forever, and cancelling the update cancels the
        // lookups with it. (A blocking getAllByName may keep its IO
        // thread busy past the deadline; the coroutine itself no
        // longer waits for it.)
        val semaphore = Semaphore(5)
        coroutineScope {
            for (profile in profiles) {
                if (profile.serverAddress.isIpAddress()) continue

                launch {
                    semaphore.withPermit {
                        try {
                            val results = withTimeoutOrNull(10_000) {
                                withContext(Dispatchers.IO) {
                                    if (
                                        RiftApp.underlyingNetwork != null &&
                                        DataStore.enableFakeDns &&
                                        DataStore.serviceState.started &&
                                        DataStore.serviceMode == Key.MODE_VPN
                                    ) {
                                        // FakeDNS
                                        RiftApp.underlyingNetwork!!
                                            .getAllByName(profile.serverAddress)
                                            .filterNotNull()
                                    } else {
                                        // System DNS is enough (when VPN connected, it uses v2ray-core)
                                        InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                                    }
                                }
                            } ?: error("DNS lookup timed out")
                            if (results.isEmpty()) error("empty response")
                            rewriteAddress(profile, results, ipv6First)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
                        }
                        if (groupId != null) {
                            progress.progress++
                            GroupManager.postReload(groupId)
                        }
                    }
                }
            }
        }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) cancel()
                try {
                    GroupManager.postReload(proxyGroup.id)

                    val subscription = proxyGroup.subscription
                    if (subscription == null) {
                        Logs.w("Group ${proxyGroup.id} has no subscription, skip update")
                        finishUpdate(proxyGroup)
                        return@coroutineScope false
                    }
                    val connected = DataStore.serviceState.connected
                    val userInterface = GroupManager.userInterface

                    if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                        if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            finishUpdate(proxyGroup)
                            cancel()
                            return@coroutineScope true
                        }
                    }

                    try {
                        RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                        true
                    } catch (e: CancellationException) {
                        // N5：取消的更新不是失败——重抛，外层只清标志、
                        // 不弹“更新失败”。
                        throw e
                    } catch (e: Throwable) {
                        Logs.w(e)
                        userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                        finishUpdate(proxyGroup)
                        false
                    }
                } catch (e: CancellationException) {
                    // N3/N5：取消（重复更新，或 postReload 在取消后
                    // 抛错）——清标志、不报失败。
                    finishUpdate(proxyGroup)
                    throw e
                } catch (e: Throwable) {
                    // N3：postReload / 更新前步骤抛错——清标志、不让
                    // updating 卡死。
                    Logs.w(e)
                    GroupManager.userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}