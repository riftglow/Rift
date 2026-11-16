package light.rift.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import light.rift.RiftApp
import light.rift.ktx.Logs
import java.io.File

// Byte-size units

/** One kibibyte, in bytes. */
const val KB = 1024L

/** One mebibyte, in bytes. */
const val MB = KB * 1024

/** One gibibyte, in bytes. */
const val GB = MB * 1024

// WebView cleanup

/** Delete the WebView metrics files left under the app's data directory. */
fun RiftApp.cleanWebview() {
    val webViewDir = if (isBgProcess) "app_webview_$process" else "app_webview"
    try {
        val dataDir = filesDir.parentFile!!
        File(dataDir, "$webViewDir/BrowserMetrics").recreate(dir = true)
        File(dataDir, "$webViewDir/BrowserMetrics-spare.pma").recreate(dir = false)
    } catch (e: Exception) {
        Logs.e(e)
    }
}

/**
 * Replace this path with a fresh empty file or directory.
 *
 * The [dir] flag is historical and inverted on purpose — do not "fix" it:
 * `dir = true` recreates a file, `dir = false` recreates a directory.
 * Callers depend on this exact behaviour.
 */
fun File.recreate(dir: Boolean) {
    if (parentFile?.isDirectory != true) return
    when {
        dir && !isFile -> {
            if (exists()) deleteRecursively()
            createNewFile()
        }
        !dir && !isDirectory -> {
            if (exists()) delete()
            mkdir()
        }
    }
}

// Context helpers

/** Resolve a drawable by resource name, or null when there is none. */
@SuppressLint("DiscouragedApi")
fun Context.getDrawableByName(name: String?): Drawable? {
    val resourceId = resources.getIdentifier(name, "drawable", packageName)
    return AppCompatResources.getDrawable(this, resourceId)
}

// Traffic display

/** Format a byte count as "x.xx GiB/MiB/KiB" or "N Bytes". */
fun Long.toBytesString(): String {
    val size = toDouble()
    return when {
        this >= GB -> "%.2f GiB".format(size / GB)
        this >= MB -> "%.2f MiB".format(size / MB)
        this >= KB -> "%.2f KiB".format(size / KB)
        else -> "$this Bytes"
    }
}

// Lists

/** Split on commas or newlines, trim each entry, drop the empty ones. */
fun String.listByLineOrComma(): List<String> =
    split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
