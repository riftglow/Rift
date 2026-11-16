package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.socks.SOCKSBean
import light.rift.core.ui.SimpleMenuPreference

class SocksSettingsActivity : ProfileSettingsActivity<SOCKSBean>() {
    override fun createEntity() = SOCKSBean()

    override fun SOCKSBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverProtocolInt = protocol
        DataStore.serverUsername = username
        DataStore.serverPassword = password
        DataStore.profileCacheStore.putBoolean("sUoT", sUoT)
    }

    override fun SOCKSBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        protocol = DataStore.serverProtocolInt
        username = DataStore.serverUsername
        password = DataStore.serverPassword
        sUoT = DataStore.profileCacheStore.getBoolean("sUoT") ?: false
    }

    private fun <T : Preference> PreferenceFragmentCompat.pref(key: String): T =
        findPreference(key)!!

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.socks_preferences)
        pref<EditTextPreference>(Key.SERVER_PORT)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        val password = pref<EditTextPreference>(Key.SERVER_PASSWORD).apply {
            summaryProvider = PasswordSummaryProvider
        }
        val protocol = pref<SimpleMenuPreference>(Key.SERVER_PROTOCOL)

        fun updateProtocol(version: Int) {
            password.isVisible = version == SOCKSBean.PROTOCOL_SOCKS5
        }

        updateProtocol(DataStore.protocolVersion)
        protocol.setOnPreferenceChangeListener { _, newValue ->
            updateProtocol((newValue as String).toInt())
            true
        }
    }
}
