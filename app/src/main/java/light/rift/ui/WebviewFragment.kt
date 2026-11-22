package light.rift.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import light.rift.ktx.RiftAlertDialogBuilder
import light.rift.BuildConfig
import light.rift.R
import light.rift.database.DataStore
import light.rift.databinding.LayoutWebviewBinding
import light.rift.core.utils.WebViewUtil

// A fragment must keep a public no-arg constructor, or state restore will crash.

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {

    lateinit var mWebView: WebView

    /** Set once the WebView is destroyed; destroy() must only run once per instance. */
    private var webViewDestroyed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        mWebView = setupWebView(LayoutWebviewBinding.bind(view).webview)
        webViewDestroyed = false
        loadDashboard()
    }

    /** 加载仪表盘。Clash API secret（0.2.29 -26）放 URL 参数里带过去；不改存着的 yacdURL。
     * 注意：secret 只能放 query——yacd 只从 query（或它自己的 cookie）读 secret，
     * shouldInterceptRequest 拿不到 POST/PUT 的请求体，改 header 注入会断掉切节点；
     * localStorage/cookie 的键又是 yacd 版本私有的。所以加载时仍带 secret，
     * 加载完立刻用 replaceState 把 query 从地址栏抹掉：后续 API 请求的 Referer
     * 与历史记录里不再带 secret。首次加载瞬间 URL 里仍带 secret，这是 yacd
     * 的既定机制，改不掉。 */
    private fun loadDashboard() {
        val url = DataStore.yacdURL
        val secret = DataStore.clashApiSecret
        mWebView.loadUrl(
            if (secret.isNotBlank()) url + (if ("?" in url) "&" else "?") + "secret=" + secret else url
        )
    }

    /** Stops the WebView and releases its native resources (M-7). */
    private fun destroyWebView() {
        if (::mWebView.isInitialized && !webViewDestroyed) {
            webViewDestroyed = true
            mWebView.onPause()
            mWebView.removeAllViews()
            mWebView.destroy()
        }
    }

    override fun onDestroyView() {
        destroyWebView()
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // secret 已由 yacd 读走（存进它自己的 cookie/内存），把 query
                // 从地址栏抹掉：后续 API 请求的 Referer 与历史记录里不再带 secret。
                // 只在 URL 真带 secret 时才动，避免多余地改写地址。
                if (url != null && "secret=" in url) {
                    view?.evaluateJavascript(
                        "history.replaceState(null,'',location.pathname+location.hash);",
                        null
                    )
                }
            }
        }
        return webView
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> showSetUrlDialog()
            R.id.close -> destroyWebView()
        }
        return true
    }

    private fun showSetUrlDialog() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(DataStore.yacdURL)
        }
        RiftAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.yacdURL = input.text.toString()
                loadDashboard()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
