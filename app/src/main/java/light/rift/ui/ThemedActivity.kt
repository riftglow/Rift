package light.rift.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import light.rift.R
import light.rift.utils.Theme

/**
 * Base activity for every Rift screen: applies the Rift theme (or the
 * dialog variant), keeps night mode in sync, and recreates itself when the
 * system UI mode flips. On API 35+ it also pads the app bar below the
 * status bar using the real inset height instead of a fixed dp guess.
 */
abstract class ThemedActivity : AppCompatActivity {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    /** Theme resource id passed to [setTheme], remembered for subclasses. */
    var themeResId = 0

    /** UI mode bitmask seen at the last configuration change. */
    var uiMode = 0

    /** True when this activity is shown as a dialog (different theme). */
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (isDialog) {
            Theme.applyDialog(this)
        } else {
            Theme.apply(this)
        }
        Theme.applyNightTheme()
        super.onCreate(savedInstanceState)
        uiMode = resources.configuration.uiMode
        if (Build.VERSION.SDK_INT >= 35) {
            installAppBarInsets()
        }
    }

    private fun installAppBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            findViewById<AppBarLayout>(R.id.appbar)?.updatePadding(top = statusBarTop)
            insets
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.uiMode == uiMode) return
        uiMode = newConfig.uiMode
        ActivityCompat.recreate(this)
    }

    /** Show a snackbar from a string resource. */
    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)

    /** Show a snackbar with free text; its message wraps to 10 lines. */
    fun snackbar(text: CharSequence): Snackbar {
        val bar = snackbarInternal(text)
        bar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
        return bar
    }

    /**
     * Builds the snackbar. Subclasses provide the anchor view; the base
     * implementation has no view to anchor to.
     */
    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
