package light.rift.core.proxy.config

import android.os.Bundle
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.preference.OnPreferenceDataStoreChangeListener
import light.rift.core.ui.EditConfigPreference
import light.rift.ui.profile.ProfileSettingsActivity

/**
 * Editor for a raw "custom config" profile: a single text field holding
 * the whole config, plus a switch for whether the profile is outbound-only.
 * The bean and the on-screen fields are synced through [init] and
 * [serialize]; any preference change marks the profile dirty.
 */
class ConfigSettingActivity :
    ProfileSettingsActivity<ConfigBean>(),
    OnPreferenceDataStoreChangeListener {

    /** Cache-store flag: true when this profile is outbound-only. */
    private val isOutboundOnlyKey = "isOutboundOnly"

    /** The raw-config editor row, bound once the preferences inflate. */
    private lateinit var editConfigPreference: EditConfigPreference

    override fun createEntity() = ConfigBean()

    /** Load the bean into the editing fields. */
    override fun ConfigBean.init() {
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        DataStore.profileName = name
        DataStore.serverConfig = config
    }

    /** Write the editing fields back into the bean. */
    override fun ConfigBean.serialize() {
        type = if (DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        name = DataStore.profileName
        config = DataStore.serverConfig
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.config_preferences)
        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
    }

    override fun onResume() {
        super.onResume()
        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

}
