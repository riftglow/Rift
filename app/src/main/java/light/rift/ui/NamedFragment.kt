package light.rift.ui

import androidx.fragment.app.Fragment

/**
 * A fragment that knows its own display name, used by the tabbed screens
 * (tools, and anything else that labels its pages). Subclasses supply the
 * name through [name0]; the value is computed once and cached.
 */
abstract class NamedFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    /** Cached display name, resolved lazily from [name0]. */
    private val cachedName: String by lazy { name0() }

    /** Display name for tabs and titles. */
    fun name(): String = cachedName

    /** Subclasses return their display name here. */
    protected abstract fun name0(): String

}
