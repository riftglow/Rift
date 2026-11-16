package light.rift.ui

import android.os.Bundle
import light.rift.R
import light.rift.RiftApp
import light.rift.database.DataStore
import light.rift.database.ProfileManager
import light.rift.ktx.runOnMainDispatcher

/**
 * Quick-switch sheet: shows the profile list in selection mode as a
 * dialog, and when the user picks a node it becomes the selected proxy
 * and the service is reloaded.
 */
class SwitchActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    override val isDialog = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val picker = ConfigurationFragment(true, null, R.string.action_switch)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, picker)
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val previousId = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        runOnMainDispatcher {
            ProfileManager.postUpdate(previousId, true)
            ProfileManager.postUpdate(profileId, true)
        }
        RiftApp.reloadService()
        finish()
    }

}
