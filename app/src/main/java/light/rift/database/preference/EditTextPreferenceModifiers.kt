package light.rift.database.preference

import android.graphics.Typeface
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference

/**
 * Small bind-time tweaks for the edit-text preferences used across the
 * settings screens: monospace rendering, cursor placement, and numeric
 * input flavours.
 */
object EditTextPreferenceModifiers {

    /** Put the cursor at the end of whatever text is already there. */
    private fun EditText.moveCursorToEnd() {
        setSelection(text.length)
    }

    /** Render the field in a monospace font (config / JSON text). */
    object Monospace : EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            editText.typeface = Typeface.MONOSPACE
        }
    }

    /** Long host lists: allow horizontal scrolling, start at the end. */
    object Hosts : EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            editText.setHorizontallyScrolling(true)
            editText.moveCursorToEnd()
        }
    }

    /** Port numbers: digits only, at most 5 characters, single line. */
    object Port : EditTextPreference.OnBindEditTextListener {
        private val lengthLimit = arrayOf(InputFilter.LengthFilter(5))

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.filters = lengthLimit
            editText.setSingleLine()
            editText.moveCursorToEnd()
        }
    }

    /** Plain numbers: digits only, single line. */
    object Number : EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.setSingleLine()
            editText.moveCursorToEnd()
        }
    }

}
