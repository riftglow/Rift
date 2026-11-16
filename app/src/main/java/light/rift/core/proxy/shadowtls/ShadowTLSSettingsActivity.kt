package light.rift.core.proxy.shadowtls

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import light.rift.R
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.ui.profile.ProfileSettingsActivity
import light.rift.core.proxy.PreferenceBinding
import light.rift.core.proxy.PreferenceBindingManager
import light.rift.core.proxy.Type

class ShadowTLSSettingsActivity : ProfileSettingsActivity<ShadowTLSBean>() {

    override fun createEntity() = ShadowTLSBean()

    private val pbm = PreferenceBindingManager()
    private fun bind(type: Int, key: String) = pbm.add(PreferenceBinding(type, key))

    private val name = bind(Type.Text, "name")
    private val serverAddress = bind(Type.Text, "serverAddress")
    private val serverPort = bind(Type.TextToInt, "serverPort")
    private val password = bind(Type.Text, "password")
    private val version = bind(Type.TextToInt, "version")
    private val sni = bind(Type.Text, "sni")
    private val alpn = bind(Type.Text, "alpn")
    private val certificates = bind(Type.Text, "certificates")
    private val allowInsecure = bind(Type.Bool, "allowInsecure")
    private val utlsFingerprint = bind(Type.Text, "utlsFingerprint")

    override fun ShadowTLSBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun ShadowTLSBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.shadowtls_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (password.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }

}
