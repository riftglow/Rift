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

    /** 加载仪表盘。Clash API secret（0.2.29 -26）放 URL 参数里带过去；不改存着的 yacdURL。 */
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
