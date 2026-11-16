package light.rift.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import light.rift.R

/**
 * MTU picker: a dropdown of presets whose summary always shows the current
 * value. Long-pressing the row opens a dialog to type a custom MTU; values
 * outside 1000–10000 are silently rejected, and a non-numeric entry throws
 * exactly as before.
 */
class MTUPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.dropdownPreferenceStyle,
) : ListPreference(context, attrs, defStyle, 0) {

    companion object {
        /** Smallest accepted custom MTU. */
        private const val MIN_MTU = 1000

        /** Largest accepted custom MTU. */
        private const val MAX_MTU = 10000
    }

    init {
        setSummaryProvider { value.toString() }
        dialogLayoutResource = R.layout.layout_mtu_help
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            showCustomMtuDialog()
            true
        }
    }

    private fun showCustomMtuDialog() {
        val field = EditText(context).apply {
            inputType = EditorInfo.TYPE_CLASS_NUMBER
            setText(preferenceDataStore?.getString(key, "") ?: "")
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("MTU")
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = field.text.toString().toInt()
                if (typed < MIN_MTU || typed > MAX_MTU) return@setPositiveButton
                value = typed.toString()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
