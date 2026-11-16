package light.rift.core.proxy.anytls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.ktx.applyDefaultValues
import light.rift.ui.profile.ProfileSettingsActivity
import light.rift.core.proxy.PreferenceBinding
import light.rift.core.proxy.PreferenceBindingManager
import light.rift.core.proxy.Type

class AnyTLSSettingsActivity : ProfileSettingsActivity<AnyTLSBean>() {
    override fun createEntity() = AnyTLSBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private fun bind(type: Int, key: String) = pbm.add(PreferenceBinding(type, key))

    private val name = bind(Type.Text, "name")
    private val serverAddress = bind(Type.Text, "serverAddress")
    private val serverPort = bind(Type.TextToInt, "serverPort")
    private val password = bind(Type.Text, "password")
    private val sni = bind(Type.Text, "sni")
    private val alpn = bind(Type.Text, "alpn")
    private val certificates = bind(Type.Text, "certificates")
    private val allowInsecure = bind(Type.Bool, "allowInsecure")
    private val utlsFingerprint = bind(Type.Text, "utlsFingerprint")

    override fun AnyTLSBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun AnyTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    private fun <T : Preference> PreferenceFragmentCompat.pref(key: String): T =
        findPreference(key)!!

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.anytls_preferences)

        pref<EditTextPreference>(Key.SERVER_PORT)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        pref<EditTextPreference>("password").summaryProvider = PasswordSummaryProvider
    }
}
