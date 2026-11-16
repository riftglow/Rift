package light.rift.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import light.rift.R
import light.rift.ktx.USER_AGENT

/**
 * User-Agent override field. When the user leaves it blank the summary
 * shows the app's built-in [USER_AGENT] instead of an empty value.
 */
class UserAgentPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle,
    ),
) : EditTextPreference(context, attrs, defStyle) {

    public override fun notifyChanged() {
        super.notifyChanged()
    }

    override fun getSummary(): CharSequence? {
        if (text.isNullOrBlank()) return USER_AGENT
        return super.getSummary()
    }

}
