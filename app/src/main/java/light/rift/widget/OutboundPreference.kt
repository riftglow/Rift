package light.rift.widget

import android.content.Context
import android.util.AttributeSet
import light.rift.R
import light.rift.database.DataStore
import light.rift.database.ProfileManager
import light.rift.core.ui.SimpleMenuPreference

class OutboundPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
    }

    override fun getSummary(): CharSequence? {
        if (value == "3") {
            val routeOutbound = DataStore.profileCacheStore.getLong(key + "Long") ?: 0
            if (routeOutbound > 0) {
                ProfileManager.getProfile(routeOutbound)?.displayName()?.let {
                    return it
                }
            }
        }
        // Batch 2 item 5: group rules show the group name, resolved
        // synchronously here (small table, same as ProfileManager usage).
        if (value == "4") {
            val routeOutbound = DataStore.profileCacheStore.getLong(key + "Long") ?: 0
            val groupId = light.rift.fmt.outboundGroupId(routeOutbound)
            if (groupId != null) {
                light.rift.database.RiftDatabase.groupDao.getById(groupId)
                    ?.displayName()?.let {
                        return context.getString(R.string.route_group_name, it)
                    }
            }
        }
        return super.getSummary()
    }

}