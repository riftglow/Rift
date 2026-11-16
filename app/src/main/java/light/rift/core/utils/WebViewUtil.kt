package light.rift.core.utils

import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import light.rift.ktx.Logs

object WebViewUtil {
    fun onReceivedError(
        view: WebView?, request: WebResourceRequest?, error: WebResourceError?
    ) {
        if (Build.VERSION.SDK_INT >= 23 && error != null) {
            Logs.e("WebView error description: ${error.description}")
        }
        Logs.e("WebView error: ${error.toString()}")
    }
}
