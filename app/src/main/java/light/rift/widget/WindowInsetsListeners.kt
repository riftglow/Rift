package light.rift.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Window-insets listener for scrolling lists: pads the list's bottom by
 * the navigation-bar height so the last row is never hidden behind the
 * gesture bar. The insets themselves are passed through untouched.
 */
object ListListener : OnApplyWindowInsetsListener {

    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        view.updatePadding(bottom = navBarHeight)
        return insets
    }

}
