package light.rift.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import light.rift.ktx.app
import light.rift.ktx.listenForPackageChanges
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {

    lateinit var installedPackages: Map<String, PackageInfo>
    lateinit var installedApps: Map<String, ApplicationInfo>
    lateinit var packageMap: Map<String, Int>
    val uidMap = HashMap<Int, HashSet<String>>()
    val loaded = Mutex(true)
    var registerd = AtomicBoolean(false)

    // called from init (suspend)
    fun register() {
        if (registerd.getAndSet(true)) return
        reload()
        app.listenForPackageChanges(false) {
            reload()
            labelMap.clear()
        }
        loaded.unlock()
    }

    /** 查出装机应用全表：权限、Provider、meta-data 都要，后面几处都要用。 */
    @SuppressLint("InlinedApi")
    private fun queryInstalledPackages(): List<PackageInfo> {
        return app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )
    }

    /** 代理分流只关心能联网的应用；系统 android 包本身没有联网权限声明，要特判留下。 */
    private fun isListable(info: PackageInfo): Boolean {
        return when (info.packageName) {
            "android" -> true
            else -> info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
        }
    }

    /** 按 uid 建包名索引：分流按 uid 查应用时用。 */
    private fun rebuildUidMap(installed: List<ApplicationInfo>) {
        packageMap = installed.associate { it.packageName to it.uid }
        uidMap.clear()
        for (info in installed) {
            uidMap.getOrPut(info.uid) { HashSet() }.add(info.packageName)
        }
    }

    @SuppressLint("InlinedApi")
    fun reload() {
        val rawPackageInfo = queryInstalledPackages()

        installedPackages = rawPackageInfo.filter(::isListable)
            .associateBy { it.packageName }

        val installed = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        installedApps = installed.associateBy { it.packageName }
        rebuildUidMap(installed)
    }

    operator fun get(uid: Int) = uidMap[uid]
    operator fun get(packageName: String) = packageMap[packageName]

    fun awaitLoadSync() {
        if (::packageMap.isInitialized) {
            return
        }
        if (!registerd.get()) {
            register()
            return
        }
        runBlocking {
            loaded.withLock {
                // just await
            }
        }
    }

    private val labelMap = mutableMapOf<String, String>()
    fun loadLabel(packageName: String): String {
        var label = labelMap[packageName]
        if (label != null) return label
        val info = installedApps[packageName] ?: return packageName
        label = info.loadLabel(app.packageManager).toString()
        labelMap[packageName] = label
        return label
    }

}
