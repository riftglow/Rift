package light.rift.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import light.rift.BuildConfig
import light.rift.R
import light.rift.RiftApp
import light.rift.core.utils.Util
import light.rift.database.DataStore
import light.rift.ktx.*
import libcore.Libcore
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * 应用内更新（0.2.29 -26，第一批）：
 * - 关于页已有手动检查（查 GitHub、弹对话框、点确认开浏览器到发布页）；这里把它
 *   升级为应用内下载安装，并加每天自动查一次。
 * - 只查正式版（releases/latest），预览版不进更新检查（预览走聊天发包）。
 * - 参考实现（Capricornus007 40613d0 移植 behindflower）没有签名校验，也没有
 *   自动检查；这两项是 Rift 自己补的。另注意参考实现的 cache_paths 坑在 Rift
 *   不存在——Rift 的 cache_paths 是 path="/"，整层 cache 都覆盖，APK 放根目录就行。
 * - 下载用 libcore 客户端（连着 VPN 时走 mixed 入口认证，见 trySocks5Auth），
 *   边下边写盘（WriteTo），不占内存；进度是不定 spinner（19MB 左右，很快）。
 */
object AppUpdater {
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/riftglow/Rift/releases/latest"
    private const val UPDATE_APK_NAME = "update.apk"
    private const val UPDATE_APK_TMP_NAME = "update.apk.tmp"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Asset(val name: String, val downloadUrl: String)
    data class Release(val name: String, val assets: List<Asset>)

    // 安装未知来源的授权 launcher，由 MainActivity 注册；授权回来时重试待安装的包。
    private var installLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingInstall: File? = null

    fun registerInstallLauncher(activity: AppCompatActivity): ActivityResultLauncher<Intent> {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingInstall?.let { installApk(activity, it) }
                pendingInstall = null
            }
        }
        installLauncher = launcher
        return launcher
    }

    /** 从系统设置回来时再认一次安装授权：用户在设置里开了权限后按返回键，
     * launcher 回调拿到的是 RESULT_CANCELED（不是 RESULT_OK），之前只认
     * RESULT_OK 会丢掉这次待安装的包。MainActivity.onResume 里调。 */
    fun retryPendingInstall(activity: Activity) {
        val file = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) return
        pendingInstall = null
        installApk(activity, file)
    }

    private fun newHttpClient() = Libcore.newHttpClient().apply {
        modernTLS()
        if (DataStore.serviceState.connected) {
            if (DataStore.localProxyAuthEnabled) {
                trySocks5Auth(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
            } else {
                trySocks5(DataStore.mixedPort)
            }
        }
    }

    /** 查 GitHub 最新正式版。失败返回 null（静默，调用方按无更新处理）。 */
    private fun fetchLatestRelease(): Release? {
        val client = newHttpClient()
        return try {
            val response = client.newRequest().apply { setURL(RELEASES_LATEST_URL) }.execute()
            val release = JSONObject(Util.getStringBox(response.contentString))
            val name = release.optString("name")
            val assets = release.optJSONArray("assets")?.let { arr ->
                List(arr.length()) { i ->
                    val a = arr.getJSONObject(i)
                    Asset(a.optString("name"), a.optString("browser_download_url"))
                }
            } ?: emptyList()
            if (name.isBlank()) null else Release(name, assets)
        } catch (e: Exception) {
            Logs.w(e)
            null
        } finally {
            client.close()
        }
    }

    /** 语义版本比较：去掉 v 前缀，按数字段逐段比；长度不同时短的补 0。
     * 返回 >0 表示 a 比 b 新，=0 相同，<0 表示 a 比 b 旧。 */
    private fun compareVersion(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".")
        val pb = b.trim().removePrefix("v").split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    fun hasUpdate(releaseName: String): Boolean {
        if (releaseName.isBlank()) return false
        // 正式版名就是 tag 名（v0.2.29）；预览版的 VERSION_NAME 也是 0.2.29，
        // 所以预览版上最新正式版 0.2.29 不算更新（版本号相等）。
        // 子串比较会误判（0.2.29 的 contains("0.2.2") 是真、0.2.2 的
        // contains("0.2.29") 是假），改按数字段逐段比。
        return compareVersion(releaseName, BuildConfig.VERSION_NAME) > 0
    }

    /** 选安装包：按本机 ABI 顺序找 -$abi-（如 -arm64-v8a-release.apk）；没有就找通用包；再没有取第一个 APK。 */
    private fun pickApkAsset(release: Release): Asset? {
        val apks = release.assets.filter { it.name.endsWith(".apk") && it.downloadUrl.isNotBlank() }
        if (apks.isEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { "-$abi-" in it.name || it.name.endsWith("-$abi.apk") }?.let { return it }
        }
        apks.firstOrNull { "universal" in it.name.lowercase() }?.let { return it }
        return apks.first()
    }

    /** 签名校验（参考实现没有，Rift 自己补的）：下载的 APK 必须和已装应用同一签名才装。 */
    @Suppress("DEPRECATION")
    private fun apkSignatures(context: Context, apkFile: File): Array<Signature>? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)?.signatures
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSignatures(context: Context): Array<Signature>? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)?.signatures
        }
    }

    private fun verifyApkSignature(context: Context, apkFile: File): Boolean {
        val apk = apkSignatures(context, apkFile) ?: return false
        val installed = installedSignatures(context) ?: return false
        return apk.contentEquals(installed)
    }

    /** 有更新时的对话框：确认后应用内下载安装（以前是打开浏览器到发布页）。 */
    fun showUpdateDialog(activity: Activity, release: Release) {
        RiftAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setMessage(
                activity.getString(
                    R.string.update_dialog_message,
                    RiftApp.appVersionNameForDisplay,
                    release.name
                )
            )
            .setPositiveButton(R.string.update_download_install) { _, _ ->
                downloadAndInstall(activity, release)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    /** 手动检查（关于页）：有更新弹对话框，没有 Toast 提示。 */
    fun checkManually(activity: Activity) {
        runOnIoDispatcher {
            val release = fetchLatestRelease()
            runOnMainDispatcher {
                if (activity.isFinishing || activity.isDestroyed) return@runOnMainDispatcher
                if (release != null && hasUpdate(release.name)) {
                    showUpdateDialog(activity, release)
                } else if (release != null) {
                    Toast.makeText(activity, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, R.string.check_update_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 每天自动查一次（MainActivity 启动时调）：静默，有更新才弹对话框。 */
    fun checkDaily(activity: Activity) {
        val now = System.currentTimeMillis()
        if (now - DataStore.lastUpdateCheckTime < DAY_MS) return
        val activityRef = WeakReference(activity)
        runOnIoDispatcher {
            val release = fetchLatestRelease() ?: return@runOnIoDispatcher
            // 请求成功才盖时间戳：之前先盖再请求，请求失败（断网）会白白
            // 消耗掉当天的检查机会，第二天之前不再查。
            DataStore.lastUpdateCheckTime = System.currentTimeMillis()
            if (!hasUpdate(release.name)) return@runOnIoDispatcher
            runOnMainDispatcher {
                val act = activityRef.get() ?: return@runOnMainDispatcher
                if (act.isFinishing || act.isDestroyed) return@runOnMainDispatcher
                showUpdateDialog(act, release)
            }
        }
    }

    /** 下载并安装。已下载过同一版本就直接装，不重复下。 */
    fun downloadAndInstall(activity: Activity, release: Release) {
        val asset = pickApkAsset(release)
        if (asset == null) {
            Toast.makeText(activity, R.string.update_no_apk, Toast.LENGTH_SHORT).show()
            return
        }
        val apkFile = File(app.cacheDir, UPDATE_APK_NAME)
        if (apkFile.exists() && apkFile.length() > 0 && DataStore.pendingUpdateVersion == release.name) {
            installApk(activity, apkFile)
            return
        }
        // 下载中对话框（不定进度；19MB 左右，几秒到几十秒）。
        val dialog = RiftAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading)
            .setMessage(activity.getString(R.string.update_downloading_message, release.name))
            .setCancelable(false)
            .show()
        runOnIoDispatcher {
            try {
                val tmpFile = File(app.cacheDir, UPDATE_APK_TMP_NAME)
                val client = newHttpClient()
                try {
                    val response = client.newRequest().apply { setURL(asset.downloadUrl) }.execute()
                    // gomobile 里 Go 的 error 返回会变成 Kotlin 异常，所以 writeTo
                    // 失败（磁盘满、断网）会直接抛到外层 catch；这里再补一道
                    // Content-Length 对照：服务器给了长度而实际写少了，说明静默截断。
                    response.writeTo(tmpFile.absolutePath)
                    val expected = try {
                        Util.getStringBox(response.getHeader("Content-Length"))?.toLongOrNull()
                    } catch (_: Throwable) {
                        null
                    }
                    if (expected != null && expected >= 0 && tmpFile.length() != expected) {
                        throw IllegalStateException(
                            "download size mismatch: expected $expected bytes, got ${tmpFile.length()}"
                        )
                    }
                } finally {
                    client.close()
                }
                if (tmpFile.length() <= 0) throw IllegalStateException("empty download")
                if (!tmpFile.renameTo(apkFile)) throw IllegalStateException("rename failed")
                DataStore.pendingUpdateVersion = release.name
                runOnMainDispatcher {
                    dialog.dismiss()
                    installApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    dialog.dismiss()
                    Toast.makeText(activity, e.readableMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 安装 APK：先验签名，再看未知来源授权，最后调系统安装器。 */
    fun installApk(activity: Activity, apkFile: File) {
        if (!verifyApkSignature(activity, apkFile)) {
            apkFile.delete()
            DataStore.pendingUpdateVersion = ""
            Toast.makeText(activity, R.string.update_signature_mismatch, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            // 未授权安装未知来源：先存下待安装的包，去系统设置里开，回来再装。
            pendingInstall = apkFile
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            installLauncher?.launch(intent) ?: activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".cache", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
