package light.rift.core.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ui.profile.ConfigEditActivity

/**
 * Preference row that opens the raw-config editor. Its summary shows how
 * many lines the current config has, or "not set" when it is empty.
 * [useConfigStore] switches the row to read/write a named config in the
 * configuration store instead of the active server config.
 */
class EditConfigPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr,
    )
    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int,
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        intent = Intent(context, ConfigEditActivity::class.java)
    }

    /** Data-store key holding the config this row edits. */
    var configKey = Key.SERVER_CONFIG

    /** True when [configKey] lives in the configuration store. */
    var useConfigStore = false

    /** Point this row at a named config inside the configuration store. */
    fun useConfigStore(key: String) {
        try {
            configKey = key
            useConfigStore = true
            intent = intent!!.apply {
                putExtra("useConfigStore", "1")
                putExtra("key", key)
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    override fun getSummary(): CharSequence {
        val config = readConfig()
        if (config.isBlank()) {
            return app.resources.getString(androidx.preference.R.string.not_set)
        }
        return app.resources.getString(R.string.lines, config.split('\n').size)
    }

    private fun readConfig(): String {
        val stored = if (useConfigStore) {
            DataStore.configurationStore.getString(configKey)
        } else {
            DataStore.serverConfig
        }
        return stored ?: ""
    }

    public override fun notifyChanged() {
        super.notifyChanged()
    }

}
