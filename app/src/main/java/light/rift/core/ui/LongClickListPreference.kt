package light.rift.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import light.rift.R

/**
 * A [ListPreference] that also reports long presses on its row, so screens
 * can offer a secondary action (for example a custom-value editor) without
 * adding another row. Without a listener, a long press is consumed and
 * does nothing.
 */
class LongClickListPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.dropdownPreferenceStyle,
) : ListPreference(context, attrs, defStyle, 0) {

    private var longClickListener: View.OnLongClickListener? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener { view ->
            longClickListener?.onLongClick(view) ?: true
        }
    }

    /** Set the listener invoked on a long press of the row. */
    fun setOnLongClickListener(listener: View.OnLongClickListener) {
        longClickListener = listener
    }

}
