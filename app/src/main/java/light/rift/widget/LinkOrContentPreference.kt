package light.rift.widget

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.core.widget.addTextChangedListener
import androidx.preference.EditTextPreference
import com.google.android.material.textfield.TextInputLayout
import light.rift.R
import light.rift.ktx.app
import light.rift.ktx.readableMessage
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Edit-text preference for a value that may be a URL or a content URI.
 * While the dialog is open the input is validated live: blank and
 * `content:` values are fine, plain-http shows a cleartext warning, a
 * stray newline is flagged, and anything unparsable shows its error.
 */
class LinkOrContentPreference
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

    init {
        dialogLayoutResource = R.layout.layout_urltest_preference_dialog
        setOnBindEditTextListener { field ->
            val linkLayout = field.rootView.findViewById<TextInputLayout>(R.id.input_layout)
            val validate: () -> Unit = {
                validateLink(field.text, linkLayout)
            }
            validate()
            field.addTextChangedListener { validate() }
        }
    }

    private fun validateLink(text: CharSequence?, linkLayout: TextInputLayout) {
        if (text.isNullOrBlank()) {
            linkLayout.isErrorEnabled = false
            return
        }
        val raw = text.toString()
        try {
            if (Uri.parse(raw).scheme == "content") {
                linkLayout.isErrorEnabled = false
                return
            }
            val url = raw.toHttpUrl()
            if ("http".equals(url.scheme, true)) {
                linkLayout.error = app.getString(R.string.cleartext_http_warning)
                linkLayout.isErrorEnabled = true
            } else {
                linkLayout.isErrorEnabled = false
            }
            if (raw.contains("\n")) {
                linkLayout.error = "Unexpected new line"
            }
        } catch (e: Exception) {
            linkLayout.error = e.readableMessage
            linkLayout.isErrorEnabled = true
        }
    }

}
