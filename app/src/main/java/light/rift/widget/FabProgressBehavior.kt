package light.rift.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Keeps a [CircularProgressIndicator] the same size as the FAB it is
 * anchored to: the ring wraps the button, growing by its own track
 * thickness, and only requests a new layout when the size actually changed.
 */
class FabProgressBehavior(
    context: Context,
    attrs: AttributeSet?,
) : CoordinatorLayout.Behavior<CircularProgressIndicator>(context, attrs) {

    override fun layoutDependsOn(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        dependency: View,
    ): Boolean {
        val anchorId = (child.layoutParams as CoordinatorLayout.LayoutParams).anchorId
        return dependency.id == anchorId
    }

    override fun onLayoutChild(
        parent: CoordinatorLayout,
        child: CircularProgressIndicator,
        layoutDirection: Int,
    ): Boolean {
        val anchor = parent.getDependencies(child).single()
        val wantedSize = anchor.measuredHeight + child.trackThickness
        if (child.indicatorSize == wantedSize) return false
        child.indicatorSize = wantedSize
        return true
    }

}
