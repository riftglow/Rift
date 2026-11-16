package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import light.rift.R
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.wireguard.WireGuardBean
import light.rift.core.proxy.PreferenceBinding
import light.rift.core.proxy.PreferenceBindingManager
import light.rift.core.proxy.Type

class WireGuardSettingsActivity : ProfileSettingsActivity<WireGuardBean>() {

    override fun createEntity() = WireGuardBean()

    private val pbm = PreferenceBindingManager()
    private fun bind(type: Int, key: String) = pbm.add(PreferenceBinding(type, key))

    private val name = bind(Type.Text, "name")
    private val serverAddress = bind(Type.Text, "serverAddress")
    private val serverPort = bind(Type.TextToInt, "serverPort")
    private val localAddress = bind(Type.Text, "localAddress")
    private val privateKey = bind(Type.Text, "privateKey")
    private val peerPublicKey = bind(Type.Text, "peerPublicKey")
    private val peerPreSharedKey = bind(Type.Text, "peerPreSharedKey")
    private val mtu = bind(Type.TextToInt, "mtu")
    private val reserved = bind(Type.Text, "reserved")

    override fun WireGuardBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun WireGuardBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.wireguard_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (privateKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (mtu.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    }

}
