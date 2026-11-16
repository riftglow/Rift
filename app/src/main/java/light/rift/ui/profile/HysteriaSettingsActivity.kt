package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.hysteria.HysteriaBean
import light.rift.ktx.applyDefaultValues

class HysteriaSettingsActivity : ProfileSettingsActivity<HysteriaBean>() {

    override fun createEntity() = HysteriaBean().applyDefaultValues()

    override fun HysteriaBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPorts = serverPorts
        DataStore.serverObfs = obfuscation
        DataStore.serverPassword = authPayload
        DataStore.serverSNI = sni
        DataStore.serverCertificates = caText
        DataStore.serverAllowInsecure = allowInsecure
        DataStore.serverUploadSpeed = uploadMbps
        DataStore.serverDownloadSpeed = downloadMbps
        DataStore.serverHopInterval = hopInterval
    }

    override fun HysteriaBean.serialize() {
        name = DataStore.profileName
        // 仅支持 Hysteria2（2026-11-16 删 v1），版本写死 2。
        protocolVersion = 2
        protocol = HysteriaBean.PROTOCOL_UDP
        serverAddress = DataStore.serverAddress
        serverPorts = DataStore.serverPorts
        obfuscation = DataStore.serverObfs
        authPayload = DataStore.serverPassword
        sni = DataStore.serverSNI
        caText = DataStore.serverCertificates
        allowInsecure = DataStore.serverAllowInsecure
        uploadMbps = DataStore.serverUploadSpeed
        downloadMbps = DataStore.serverDownloadSpeed
        hopInterval = DataStore.serverHopInterval
    }

    private fun <T : Preference> PreferenceFragmentCompat.pref(key: String): T =
        findPreference(key)!!

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.hysteria_preferences)

        // 仅 Hysteria2：密码项标题固定为“密码”，v1 的认证类型/协议/
        // ALPN/接收窗口/MTU 开关已随 v1 一并删除。
        val authPayload = pref<EditTextPreference>(Key.SERVER_PASSWORD)
        authPayload.title = resources.getString(R.string.password)

        pref<EditTextPreference>(Key.SERVER_UPLOAD_SPEED)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref<EditTextPreference>(Key.SERVER_DOWNLOAD_SPEED)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        authPayload.summaryProvider = PasswordSummaryProvider
        pref<EditTextPreference>(Key.SERVER_OBFS).summaryProvider = PasswordSummaryProvider

        pref<EditTextPreference>(Key.SERVER_HOP_INTERVAL)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
    }

}
