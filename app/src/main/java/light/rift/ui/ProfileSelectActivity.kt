package light.rift.ui

import android.content.Intent
import android.os.Bundle
import light.rift.R
import light.rift.database.ProxyEntity

/**
 * Node picker: shows the profile list in selection mode and returns the
 * profile the user tapped (or nothing, when the user backs out).
 */
class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        /** Parcelled [ProxyEntity] to pre-select, if any. */
        const val EXTRA_SELECTED = "selected"

        /** Result extra: the id of the profile the user picked. */
        const val EXTRA_PROFILE_ID = "id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preselected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        val picker = ConfigurationFragment(true, preselected, R.string.select_profile)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, picker)
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        val result = Intent().apply { putExtra(EXTRA_PROFILE_ID, profileId) }
        setResult(RESULT_OK, result)
        finish()
    }

}
