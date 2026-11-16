package light.rift.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.preference.EditTextPreferenceModifiers
import light.rift.fmt.http.HttpBean
import light.rift.fmt.trojan.TrojanBean
import light.rift.fmt.v2ray.StandardV2RayBean
import light.rift.fmt.v2ray.VMessBean
import light.rift.core.proxy.PreferenceBinding
import light.rift.core.proxy.PreferenceBindingManager
import light.rift.core.proxy.Type
import light.rift.core.ui.SimpleMenuPreference

abstract class StandardV2RaySettingsActivity : ProfileSettingsActivity<StandardV2RayBean>() {

    var tmpBean: StandardV2RayBean? = null

    private val pbm = PreferenceBindingManager()

    // short hands for the field table below; the Type and the bean field name are unchanged
    private fun textField(field: String) = pbm.add(PreferenceBinding(Type.Text, field))
    private fun intField(field: String) = pbm.add(PreferenceBinding(Type.TextToInt, field))
    private fun boolField(field: String) = pbm.add(PreferenceBinding(Type.Bool, field))

    private val name = textField("name")
    private val serverAddress = textField("serverAddress")
    private val serverPort = intField("serverPort")
    private val uuid = textField("uuid")
    private val username = textField("username")
    private val password = textField("password")
    private val alterId = intField("alterId")
    private val encryption = textField("encryption")
    private val type = textField("type")
    private val host = textField("host")
    private val path = textField("path")
    private val packetEncoding = intField("packetEncoding")
    private val wsMaxEarlyData = intField("wsMaxEarlyData")
    private val earlyDataHeaderName = textField("earlyDataHeaderName")
    private val security = textField("security")
    private val sni = textField("sni")
    private val alpn = textField("alpn")
    private val certificates = textField("certificates")
    private val allowInsecure = boolField("allowInsecure")
    private val utlsFingerprint = textField("utlsFingerprint")
    private val realityPubKey = textField("realityPubKey")
    private val realityShortId = textField("realityShortId")

    private val enableECH = boolField("enableECH")
    private val echConfig = textField("echConfig")

    private val enableMux = boolField("enableMux")
    private val muxPadding = boolField("muxPadding")
    private val muxType = intField("muxType")
    private val muxConcurrency = intField("muxConcurrency")

    private val xhttpMode = textField("xhttpMode")
    private val xhttpExtra = textField("xhttpExtra")

    override fun StandardV2RayBean.init() {
        if (this is TrojanBean) {
            // trojan has no uuid of its own: the uuid row edits the password instead,
            // and the separate password row is switched off
            this@StandardV2RaySettingsActivity.uuid.fieldName = "password"
            this@StandardV2RaySettingsActivity.password.disable = true
        }

        // keep a handle on the bean being edited so the page can tell which protocol it is
        tmpBean = this
        pbm.writeToCacheAll(this)
    }

    override fun StandardV2RayBean.serialize() {
        pbm.fromCacheAll(this)
    }

    private lateinit var securityCategory: PreferenceCategory
    private lateinit var tlsCamouflageCategory: PreferenceCategory
    private lateinit var wsCategory: PreferenceCategory
    private lateinit var xhttpCategory: PreferenceCategory
    private lateinit var echCategory: PreferenceCategory

    // port rows only accept numbers
    private fun PreferenceBinding.bindPortInput() {
        preference.apply {
            this as EditTextPreference
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.standard_v2ray_preferences)
        pbm.setPreferenceFragment(this)
        securityCategory = findPreference(Key.SERVER_SECURITY_CATEGORY)!!
        tlsCamouflageCategory = findPreference(Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)!!
        echCategory = findPreference(Key.SERVER_ECH_CATEORY)!!
        wsCategory = findPreference(Key.SERVER_WS_CATEGORY)!!
        xhttpCategory = findPreference("serverXhttpCategory")!!


        // vmess/vless/http/trojan
        val isHttp = tmpBean is HttpBean
        val isVmess = tmpBean is VMessBean && tmpBean?.isVLESS == false
        val isVless = tmpBean?.isVLESS == true

        serverPort.bindPortInput()
        alterId.bindPortInput()

        uuid.preference.summaryProvider = PasswordSummaryProvider

        // rows that only make sense for some protocols
        type.preference.isVisible = !isHttp
        uuid.preference.isVisible = !isHttp
        packetEncoding.preference.isVisible = isVmess || isVless
        alterId.preference.isVisible = isVmess
        encryption.preference.isVisible = isVmess || isVless
        username.preference.isVisible = isHttp
        password.preference.isVisible = isHttp

        if (tmpBean is TrojanBean) {
            uuid.preference.title = resources.getString(R.string.password)
        }

        encryption.preference.apply {
            this as SimpleMenuPreference
            if (isVless) {
                title = resources.getString(R.string.xtls_flow)
                setIcon(R.drawable.ic_baseline_stream_24)
                setEntries(R.array.xtls_flow_value)
                setEntryValues(R.array.xtls_flow_value)
            } else {
                setEntries(R.array.vmess_encryption_value)
                setEntryValues(R.array.vmess_encryption_value)
            }
        }

        // menu with listener

        type.preference.apply {
            updateView(type.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateView(newValue as String)
                true
            }
        }

        security.preference.apply {
            updateTls(security.readStringFromCache())
            this as SimpleMenuPreference
            setOnPreferenceChangeListener { _, newValue ->
                updateTls(newValue as String)
                true
            }
        }
    }

    // host/path rows follow the transport; only the rows a transport actually uses are shown
    private fun showHostAndPath(hostTitle: Int, pathTitle: Int, show: Boolean) {
        host.preference.setTitle(hostTitle)
        path.preference.setTitle(pathTitle)
        host.preference.isVisible = show
        path.preference.isVisible = show
    }

    private fun updateView(network: String) {
        host.preference.isVisible = false
        path.preference.isVisible = false
        wsCategory.isVisible = false
        xhttpCategory.isVisible = false

        when (network) {
            "tcp" -> showHostAndPath(R.string.http_host, R.string.http_path, show = false)
            "http" -> showHostAndPath(R.string.http_host, R.string.http_path, show = true)
            "ws" -> {
                showHostAndPath(R.string.ws_host, R.string.ws_path, show = true)
                wsCategory.isVisible = true
            }

            "grpc" -> {
                path.preference.setTitle(R.string.grpc_service_name)
                path.preference.isVisible = true
            }

            "httpupgrade" -> showHostAndPath(
                R.string.http_upgrade_host, R.string.http_upgrade_path, show = true
            )

            "xhttp" -> {
                showHostAndPath(R.string.xhttp_host, R.string.xhttp_path, show = true)
                xhttpCategory.isVisible = true
            }
        }
    }

    private fun updateTls(tls: String) {
        val usesTls = "tls" in tls
        securityCategory.isVisible = usesTls
        tlsCamouflageCategory.isVisible = usesTls
        echCategory.isVisible = usesTls
    }

}
