package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.shadowsocks.ShadowsocksBean
import light.rift.core.proxy.PreferenceBinding
import light.rift.core.proxy.PreferenceBindingManager
import light.rift.core.proxy.Type

class ShadowsocksSettingsActivity : ProfileSettingsActivity<ShadowsocksBean>() {

    override fun createEntity() = ShadowsocksBean()

    private val pbm = PreferenceBindingManager()
    private fun bind(type: Int, key: String) = pbm.add(PreferenceBinding(type, key))

    private val name = bind(Type.Text, "name")
    private val serverAddress = bind(Type.Text, "serverAddress")
    private val serverPort = bind(Type.TextToInt, "serverPort")
    private val password = bind(Type.Text, "password")
    private val method = bind(Type.Text, "method")
    private val pluginName =
        pbm.add(PreferenceBinding(Type.Text, "pluginName").apply { disable = true })
    private val pluginConfig =
        pbm.add(PreferenceBinding(Type.Text, "pluginConfig").apply { disable = true })
    private val sUoT = bind(Type.Bool, "sUoT")

    override fun ShadowsocksBean.init() {
        pbm.writeToCacheAll(this)

        DataStore.profileCacheStore.putString("pluginName", plugin.substringBefore(";"))
        DataStore.profileCacheStore.putString("pluginConfig", plugin.substringAfter(";"))
    }

    override fun ShadowsocksBean.serialize() {
        pbm.fromCacheAll(this)

        val pluginNameValue = pluginName.readStringFromCache()
        val pluginConfigValue = pluginConfig.readStringFromCache()
        plugin = if (pluginNameValue.isNotBlank()) {
            "$pluginNameValue;$pluginConfigValue"
        } else {
            ""
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.shadowsocks_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (password.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }

}
