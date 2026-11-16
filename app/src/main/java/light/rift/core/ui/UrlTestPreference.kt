package light.rift.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.res.TypedArrayUtils
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import light.rift.R
import light.rift.database.DataStore

/**
 * Connection-test URL field with a second input for the concurrency level.
 * The concurrency box is shown inside the same dialog; when the dialog is
 * accepted its value is saved, defaulting to 5 for blank, zero, or
 * otherwise invalid input.
 */
class UrlTestPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    /** The concurrency input inside the open dialog, if one is open. */
    var concurrent: EditText? = null

    init {
        dialogLayoutResource = R.layout.layout_urltest_preference_dialog
        setOnBindEditTextListener { field ->
            val dialogRoot = field.rootView
            concurrent = dialogRoot.findViewById(R.id.edit_concurrent)
            concurrent?.setText(DataStore.connectionTestConcurrent.toString())
            dialogRoot.findViewById<LinearLayout>(R.id.concurrent_layout)?.isVisible = true
        }
        setOnPreferenceChangeListener { _, _ ->
            saveConcurrent()
            true
        }
    }

    private fun saveConcurrent() {
        val typed = concurrent?.text?.toString()?.toIntOrNull()
        DataStore.connectionTestConcurrent = if (typed == null || typed <= 0) 5 else typed
    }

}
