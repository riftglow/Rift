package light.rift.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.system.OsConstants
import androidx.annotation.RequiresApi
import light.rift.RiftApp
import light.rift.bg.ServiceNotification
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.utils.DefaultNetworkListener
import light.rift.utils.PackageCache
import kotlinx.coroutines.runBlocking
import libcore.BoxPlatformInterface
import libcore.InterfaceUpdateListener
import libcore.Libcore
import libcore.RiftInterface
import libcore.NetworkInterface as LibcoreNetworkInterface
import libcore.NetworkInterfaceIterator
import libcore.StringIterator
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketException

class NativeInterface : BoxPlatformInterface, RiftInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return RiftApp.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // 官方 1.15 内核强制平台提供默认接口监视器与接口枚举（见 libcore/interface_monitor.go、
    // platform_java.go 新增的三个方法）。这里复用现成的 DefaultNetworkListener，
    // 不搬 OwnBox 的 ActiveOutboundTracker/缓存那套。
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        // 必须同步注册：内核拨号时 DefaultInterface()==nil 会报 no available network interface，
        // 所以 start 要等首次回调（Go 侧 updateDefaultInterface）完成才返回。
        runBlocking {
            DefaultNetworkListener.start(listener) { network ->
                reportDefaultInterface(listener, network)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        runBlocking {
            DefaultNetworkListener.stop(listener)
        }
    }

    private fun reportDefaultInterface(listener: InterfaceUpdateListener, network: Network?) {
        if (network == null) {
            listener.updateDefaultInterface("", -1)
            return
        }
        val name = RiftApp.connectivity.getLinkProperties(network)?.interfaceName ?: run {
            listener.updateDefaultInterface("", -1)
            return
        }
        // M-3：getByName 声明抛 SocketException；接口刚消失的网络切换竞态下
        // 正是最可能抛的点。抛错不能沿监听器回调上抛杀死 DefaultNetworkListener
        // 的 actor，失败时按无默认接口处理。
        val index = try {
            NetworkInterface.getByName(name)?.index ?: -1
        } catch (e: SocketException) {
            Logs.w(e)
            listener.updateDefaultInterface("", -1)
            return
        }
        listener.updateDefaultInterface(name, index)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        @Suppress("DEPRECATION") val networks = RiftApp.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibcoreNetworkInterface>()
        for (network in networks) {
            val linkProperties = RiftApp.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities = RiftApp.connectivity.getNetworkCapabilities(network) ?: continue
            val boxInterface = LibcoreNetworkInterface()
            boxInterface.name = linkProperties.interfaceName
            val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer = linkProperties.dnsServers.mapNotNull { it.hostAddress }
                .let { it.toStringIterator(it.size) }
            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libcore.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libcore.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libcore.InterfaceTypeEthernet
                else -> Libcore.InterfaceTypeOther
            }
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
                .onFailure { Logs.w("failed to get mtu for interface ${boxInterface.name}: $it") }
            boxInterface.addresses = networkInterface.interfaceAddresses.map { it.toPrefix() }
                .let { it.toStringIterator(it.size) }
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator(), interfaces.size)
    }

    private class InterfaceArray(
        private val iterator: Iterator<LibcoreNetworkInterface>,
        private val size: Int,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibcoreNetworkInterface = iterator.next()
        override fun length(): Int = size
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // core interface

    // Rift only uses its own compiled rule table now (no provider
    // choice): never the official assets, so downloaded rule files
    // always win over the bundled ones.
    override fun useOfficialAssets(): Boolean {
        return false
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                // 2026-11-07：后台线程里 data.proxy 可能在停止与切换竞态中
                // 被置 null，先取本地引用、为空直接 return，不再用 data.proxy!!。
                val proxy = data.proxy ?: return@runOnDefaultDispatcher
                val id = proxy.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull()
                if (id == null) {
                    // 未知 tag：以前静默 return，标题更新被丢掉也不留痕；
                    // 现在记 Logs.w 再返回。
                    Logs.w("selector_OnProxySelected: unknown tag $tag")
                    return@runOnDefaultDispatcher
                }
                val ent = RiftDatabase.proxyDao.getById(id) ?: run {
                    Logs.w("selector_OnProxySelected: no profile for id $id (tag $tag)")
                    return@runOnDefaultDispatcher
                }
                // traffic & title
                proxy.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}

private fun Iterable<String>.toStringIterator(size: Int): StringIterator {
    return object : StringIterator {
        private val it = iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): String = it.next()
        override fun length(): Int = size
    }
}

private fun InterfaceAddress.toPrefix(): String {
    return if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
    } else {
        "${address.hostAddress}/${networkPrefixLength}"
    }
}
