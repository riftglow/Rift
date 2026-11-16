package light.rift.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import light.rift.R

/**
 * Base for fragments that own a toolbar: wires the hamburger icon to the
 * navigation drawer and offers key/back hooks subclasses can override.
 */
open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    /** The toolbar found in this fragment's layout. */
    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            val host = activity as MainActivity
            host.binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /** Return true when the key event was consumed. */
    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false

    /** Return true when the back press was consumed. */
    open fun onBackPressed(): Boolean = false

}
