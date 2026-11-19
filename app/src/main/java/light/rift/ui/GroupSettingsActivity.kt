package light.rift.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import light.rift.GroupType
import light.rift.Key
import light.rift.R
import light.rift.RiftApp
import light.rift.database.*
import light.rift.database.preference.OnPreferenceDataStoreChangeListener
import light.rift.ktx.Logs
import light.rift.ktx.applyDefaultValues
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.widget.ListListener
import light.rift.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import light.rift.core.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference

    fun ProxyGroup.init() {
        // group basics
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector
        // Batch 3 (2026-11-17): group outbound mode lives in the main kv store
        // (dynamic key, no entity field); load it into the editing cache.
        DataStore.profileCacheStore.putString(
            Key.GROUP_OUTBOUND_MODE, DataStore.groupOutboundMode(id)
        )

        // front/landing proxy: value 3 means "a profile picked by hand", anything else means none
        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp = if (frontProxy >= 0) 3 else 0
        DataStore.landingProxyTmp = if (landingProxy >= 0) 3 else 0

        loadSubscriptionIntoCache(subscription ?: SubscriptionBean().applyDefaultValues())
    }

    private fun loadSubscriptionIntoCache(subscription: SubscriptionBean) {
        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
    }

    fun ProxyGroup.serialize() {
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector
        // Batch 3 (2026-11-17): write the mode back to the main kv store.
        // A brand-new group has id 0 here (assigned by createGroup afterwards);
        // it keeps the default "selector", the mode can be set once it exists.
        if (id != 0L) {
            DataStore.setGroupOutboundMode(
                id,
                DataStore.profileCacheStore.getString(Key.GROUP_OUTBOUND_MODE, "selector")
                    ?: "selector"
            )
        }

        frontProxy = if (DataStore.frontProxyTmp == 3) DataStore.frontProxy else -1
        landingProxy = if (DataStore.landingProxyTmp == 3) DataStore.landingProxy else -1

        if (type == GroupType.SUBSCRIPTION) {
            subscription = (subscription ?: SubscriptionBean().applyDefaultValues()).apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
            }
        }
    }

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    // front/landing proxy picker: choosing "custom" opens the profile picker instead of
    // committing a value; the picked profile fills the value in afterwards
    private fun OutboundPreference.bindProxyPicker(onPickCustom: () -> Unit) {
        setEntries(R.array.front_proxy_entry)
        setEntryValues(R.array.front_proxy_value)
        setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == "3") {
                onPickCustom()
                false
            } else {
                true
            }
        }
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.bindProxyPicker {
            selectProfileForAddFront.launch(
                Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
            )
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.bindProxyPicker {
            selectProfileForAddLanding.launch(
                Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java)
            )
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionUpdate = findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            groupSubscription.isVisible = isSubscription
            subscriptionUpdate.isVisible = isSubscription
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    // 种子组（未分组）不可删：万一从别处进了删除流程也拦住。
                    if (RiftDatabase.groupDao.getById(arg.groupId)?.ungrouped != true) {
                        GroupManager.deleteGroup(arg.groupId)
                    }
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
    }

    // -30：未分组允许改名，但它是种子组，设置页里不许删（删组会连组内节点一起删）。
    private var editingUngrouped = false

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                var ungrouped = false
                if (editingId == 0L) {
                    ProxyGroup().init()
                } else {
                    val entity = RiftDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ungrouped = entity.ungrouped
                    entity.init()
                }

                onMainDispatcher {
                    editingUngrouped = ungrouped
                    invalidateOptionsMenu()
                    attachPreferenceFragment()
                }
            }

        }

    }

    private fun attachPreferenceFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, MyPreferenceFragmentCompat())
            .commit()

        DataStore.dirty = false
        DataStore.profileCacheStore.registerChangeListener(this)
    }

    suspend fun saveAndExit() {

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            GroupManager.createGroup(ProxyGroup().apply { serialize() })
        } else if (needSave()) {
            val entity = RiftDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            // the subscription user info belongs to the old link; if the link changed,
            // drop it so stale info never leaks into a different subscription
            if (!keepsSameSubscription(entity)) {
                entity.subscription?.subscriptionUserinfo = ""
            }
            GroupManager.updateGroup(entity.apply { serialize() })
        }

        finish()

    }

    private fun keepsSameSubscription(entity: ProxyGroup): Boolean {
        return entity.type == GroupType.SUBSCRIPTION &&
                DataStore.groupType == GroupType.SUBSCRIPTION &&
                entity.subscription?.link == DataStore.subscriptionLink
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 未分组是种子组：可以改名，不可以删除。
        menu.findItem(R.id.action_delete)?.isVisible = !editingUngrouped
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    RiftApp.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    // both proxy pickers share one flow: resolve the picked profile, store its id,
    // then mark the preference as "custom" on the main thread
    private fun onProxyProfilePicked(
        result: ActivityResult,
        setProxyId: (Long) -> Unit,
        markCustomChoice: () -> Unit,
    ) {
        if (result.resultCode != Activity.RESULT_OK) return
        runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                result.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            setProxyId(profile.id)
            onMainDispatcher {
                markCustomChoice()
            }
        }
    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onProxyProfilePicked(
            result,
            setProxyId = { DataStore.frontProxy = it },
            markCustomChoice = { frontProxyPreference.value = "3" },
        )
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onProxyProfilePicked(
            result,
            setProxyId = { DataStore.landingProxy = it },
            markCustomChoice = { landingProxyPreference.value = "3" },
        )
    }

}
