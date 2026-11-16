package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.ssh.SSHBean
import light.rift.core.ui.SimpleMenuPreference

class SSHSettingsActivity : ProfileSettingsActivity<SSHBean>() {

    override fun createEntity() = SSHBean()

    override fun SSHBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverUsername = username
        DataStore.serverAuthType = authType
        DataStore.serverPassword = password
        DataStore.serverPrivateKey = privateKey
        DataStore.serverPassword1 = privateKeyPassphrase
        DataStore.serverCertificates = publicKey
    }

    override fun SSHBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort
        username = DataStore.serverUsername
        authType = DataStore.serverAuthType
        if (authType == SSHBean.AUTH_TYPE_PASSWORD) {
            password = DataStore.serverPassword
        } else if (authType == SSHBean.AUTH_TYPE_PRIVATE_KEY) {
            privateKey = DataStore.serverPrivateKey
            privateKeyPassphrase = DataStore.serverPassword1
        }
        publicKey = DataStore.serverCertificates
    }

    private fun <T : Preference> PreferenceFragmentCompat.pref(key: String): T =
        findPreference(key)!!

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.ssh_preferences)
        pref<EditTextPreference>(Key.SERVER_PORT)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        val password = pref<EditTextPreference>(Key.SERVER_PASSWORD).apply {
            summaryProvider = PasswordSummaryProvider
        }
        val privateKey = pref<EditTextPreference>(Key.SERVER_PRIVATE_KEY)
        val privateKeyPassphrase = pref<EditTextPreference>(Key.SERVER_PASSWORD1).apply {
            summaryProvider = PasswordSummaryProvider
        }
        val authType = pref<SimpleMenuPreference>(Key.SERVER_AUTH_TYPE)
        fun updateAuthType(type: Int = DataStore.serverAuthType) {
            val usePassword = type == SSHBean.AUTH_TYPE_PASSWORD
            val usePrivateKey = type == SSHBean.AUTH_TYPE_PRIVATE_KEY
            password.isVisible = usePassword
            privateKey.isVisible = usePrivateKey
            privateKeyPassphrase.isVisible = usePrivateKey
        }
        updateAuthType()
        authType.setOnPreferenceChangeListener { _, newValue ->
            updateAuthType((newValue as String).toInt())
            true
        }
    }

}
