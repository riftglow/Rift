package light.rift.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
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
     * localStorage/cookie 的键又是 yacd 版本私有的。所以加载时仍带 secret，
     * 加载完延后 3 秒用 replaceState 把 query 从地址栏抹掉（见 onPageFinished）：
     * 后续 API 请求的 Referer 与历史记录里不再带 secret。首次加载瞬间 URL 里仍带
     * secret，这是 yacd 的既定机制，改不掉。
     * 另见 shouldInterceptRequest（-55）：WebView 自己的请求到 127.0.0.1:9090
     * 会经 mixed 入站走代理而失败，根因见 ConnectionsFragment 的 NO_PROXY 注释；
     * 故无 body 的 API 请求（GET/DELETE）在拦截里用原生 HttpURLConnection
     * （Proxy.NO_PROXY＋Bearer）代取。POST/PUT/PATCH 带 JSON body、拦截拿不到
     * body，暂放行（切节点 PUT /proxies 的 body 是 {"name": ...}）。 */
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
                // secret 由 yacd 在启动时从 query 读走（存进它自己的配置里），把 query
                // 从地址栏抹掉：后续 API 请求的 Referer 与历史记录里不再带 secret。
                // 只在 URL 真带 secret 时才动，避免多余地改写地址。
                // 延后 3 秒再抹：yacd 是 module 脚本（约 600KB 两包），初始化读 query
                // 可能晚于 onPageFinished；立刻抹会抢在它读之前，仪表板就读不到
                // secret、停在连接表单上（-53 实机验）。3 秒足够它读完并连上。
                if (url != null && "secret=" in url) {
                    val webView = view
                    webView?.postDelayed({
                        // 3 秒内已退出仪表板就不抹，避免对已销毁的 WebView 调方法。
                        if (webViewDestroyed) return@postDelayed
                        try {
                            webView.evaluateJavascript(
                                "history.replaceState(null,'',location.pathname+location.hash);",
                                null
                            )
                        } catch (_: Exception) {
                        }
                    }, 3000)
                }
            }

            /** -55：仪表板到本地 Clash API 的请求，WebView 自己发会经 mixed 入站走代理
             * 而失败（实时连接页用 Proxy.NO_PROXY 直连才能通，见 ConnectionsFragment
             * 的注释；-54 之前两次只调 query 抹除时机都证伪）。故无 body 的 API 请求
             * （GET/DELETE）在这里用原生 HttpURLConnection（Proxy.NO_PROXY＋Bearer）
             * 代取后返回。POST/PUT/PATCH 带 JSON body、拦截拿不到 body，暂放行；
             * 失败时返回 null，让 WebView 自己再试一次，不比原来更差。 */
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val req = request ?: return null
                val uri = req.url ?: return null
                val api = apiOrigin() ?: return null
                val reqPort = when {
                    uri.port != -1 -> uri.port
                    uri.scheme == "https" -> 443
                    else -> 80
                }
                if (uri.host != api.first || reqPort != api.second) return null
                // 只拦截 API 请求（非 /ui 静态资源）；静态文件本来就能加载，不动它。
                val path = uri.path ?: return null
                if (path.startsWith("/ui")) return null
                // 只拦无 body 的方法：拦截拿不到请求体，POST/PUT/PATCH（yacd 均带
                // JSON body，如切节点 PUT /proxies 的 {"name": ...}）必须放行。
                val method = req.method.uppercase()
                if (method != "GET" && method != "DELETE") return null
                return fetchApiDirect(req)
            }
        }
        return webView
    }

    /** Clash API 的 origin（host, port），取自配置的仪表板地址 yacdURL。 */
    private fun apiOrigin(): Pair<String, Int>? {
        return try {
            val u = android.net.Uri.parse(DataStore.yacdURL)
            val host = u.host ?: return null
            val port = when {
                u.port != -1 -> u.port
                u.scheme == "https" -> 443
                else -> 80
            }
            host to port
        } catch (_: Exception) {
            null
        }
    }

    /** 用原生方式代取一条仪表板 API 请求：Proxy.NO_PROXY 直连本机＋Bearer，
     * 与 ConnectionsFragment 走同一条已验证通的路。读满 body 后才断开连接，
     * API 载荷小，不会占多少内存。任何一步失败返回 null，WebView 自己再试。 */
    private fun fetchApiDirect(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val conn = (URL(request.url.toString()).openConnection(Proxy.NO_PROXY)
                    as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = 10000
                readTimeout = 30000
                val secret = DataStore.clashApiSecret
                if (secret.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $secret")
                }
                request.requestHeaders["Accept"]?.let { setRequestProperty("Accept", it) }
                connect()
            }
            val code = conn.responseCode
            val mime = (conn.contentType ?: "application/json")
                .substringBefore(";").trim().ifBlank { "application/json" }
            val headers = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, v) -> v.joinToString(", ") }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            try {
                stream?.close()
            } catch (_: Exception) {
            }
            conn.disconnect()
            WebResourceResponse(mime, "UTF-8", code, reasonPhrase(code), headers, bytes.inputStream())
        } catch (_: Exception) {
            null
        }
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "OK"
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
