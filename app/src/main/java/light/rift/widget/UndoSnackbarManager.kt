package light.rift.widget

import com.google.android.material.snackbar.Snackbar
import light.rift.R
import light.rift.ui.ThemedActivity

/**
 * Undo support for list deletions: [remove] shows a snackbar with an Undo
 * action and only commits the deletion once the snackbar is dismissed
 * without undoing. [flush] dismisses the snackbar early, which commits
 * immediately through the same path.
 */
class UndoSnackbarManager<in T>(
    private val activity: ThemedActivity,
    private val callback: Interface<T>,
) {

    /** Receives the undo/commit callbacks for a batch of removals. */
    interface Interface<in T> {
        fun undo(actions: List<Pair<Int, T>>)
        fun commit(actions: List<Pair<Int, T>>)
    }

    /** Items removed but not yet committed, with their old positions. */
    private val recycleBin = ArrayList<Pair<Int, T>>()

    /** The snackbar currently on screen, if any. */
    private var last: Snackbar? = null

    private val dismissWatcher = object : Snackbar.Callback() {
        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
            if (last !== transientBottomBar) return
            if (event == DISMISS_EVENT_ACTION) return
            callback.commit(recycleBin)
            recycleBin.clear()
            last = null
        }
    }

    /** Stage [items] for removal and show the undo snackbar. */
    fun remove(items: Collection<Pair<Int, T>>) {
        recycleBin.addAll(items)
        val count = recycleBin.size
        val message = activity.resources.getQuantityString(R.plurals.removed, count, count)
        val bar = activity.snackbar(message)
        bar.addCallback(dismissWatcher)
        bar.setAction(R.string.undo) {
            callback.undo(recycleBin.reversed())
            recycleBin.clear()
        }
        last = bar
        bar.show()
    }

    /** Stage [items] for removal; see [remove]. */
    fun remove(vararg items: Pair<Int, T>) = remove(items.toList())

    /** Dismiss the snackbar now, committing the staged removals. */
    fun flush() {
        last?.dismiss()
    }

}
