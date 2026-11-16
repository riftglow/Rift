package light.rift.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.SparseBooleanArray
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.UiThread
import androidx.core.util.contains
import androidx.core.util.set
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import light.rift.BuildConfig
import light.rift.R
import light.rift.RiftApp
import light.rift.database.DataStore
import light.rift.databinding.LayoutAppsBinding
import light.rift.databinding.LayoutAppsItemBinding
import light.rift.ktx.Logs
import light.rift.ktx.app
import light.rift.ktx.crossFadeFrom
import light.rift.ktx.onMainDispatcher
import light.rift.ktx.runOnDefaultDispatcher
import light.rift.utils.PackageCache
import light.rift.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import light.rift.core.utils.NGUtil
import kotlin.coroutines.coroutineContext

class AppManagerActivity : ThemedActivity() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        private var instance: AppManagerActivity? = null
        /** Payload marking a row whose checkbox state changed. */
        private const val PAYLOAD_SELECTION = "switch"

        private val installedAppMap
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    private class ProxiedApp(
        private val pm: PackageManager, private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: CharSequence = appInfo.loadLabel(pm)    // cached: sorting reads it often
        val icon: Drawable get() = appInfo.loadIcon(pm)
        val uid get() = appInfo.uid
        val sys get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) : RecyclerView.ViewHolder(
        binding.root
    ),
        View.OnClickListener {
        private lateinit var item: ProxiedApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: ProxiedApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            binding.desc.text = "${app.packageName} (${app.uid})"
            binding.itemcheck.isChecked = isProxiedApp(app)
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(PAYLOAD_SELECTION)) binding.itemcheck.isChecked = isProxiedApp(item)
        }

        override fun onClick(v: View?) {
            toggleProxied(item)
            refreshIndividual()

            appsAdapter.notifyItemRangeChanged(0, appsAdapter.itemCount, PAYLOAD_SELECTION)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            PackageCache.reload()
            apps = sortApps(installedAppMap.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { ProxiedApp(packageManager, it, packageName) }
            })
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }

            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filteredApps = if (constraint.isEmpty()) apps else apps.filter {
                    it.name.contains(constraint, true) || it.packageName.contains(
                        constraint, true
                    ) || it.uid.toString().contains(constraint)
                }
                if (!sysApps) filteredApps = filteredApps.filter { !it.sys }
                count = filteredApps.size
                values = filteredApps
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<ProxiedApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }

    }

    private val loading by lazy { findViewById<View>(R.id.loading) }

    private lateinit var binding: LayoutAppsBinding
    private val proxiedUids = SparseBooleanArray()
    private var loader: Job? = null
    private var apps = emptyList<ProxiedApp>()
    private val appsAdapter = AppsAdapter()
    private var sysApps = true

    /** Selected apps first, then by name. */
    private fun sortApps(list: List<ProxiedApp>): List<ProxiedApp> =
        list.sortedWith(compareBy({ !isProxiedApp(it) }, { it.name.toString() }))

    private fun toggleProxied(app: ProxiedApp) {
        if (isProxiedApp(app)) proxiedUids.delete(app.uid) else proxiedUids[app.uid] = true
    }

    /** Writes the current selection back to the stored package list. */
    private fun refreshIndividual() {
        DataStore.individual = apps.filter { isProxiedApp(it) }
            .joinToString("\n") { it.packageName }
    }

    private fun applyFilter() {
        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
    }

    private fun initProxiedUids(str: String = DataStore.individual) {
        proxiedUids.clear()
        val installed = installedAppMap
        for (line in str.lineSequence()) {
            val app = (installed[line] ?: continue)
            val uid = app.applicationInfo?.uid ?: continue
            proxiedUids[uid] = true
        }
    }

    private fun isProxiedApp(app: ProxiedApp) = proxiedUids[app.uid]

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launchWhenCreated {
            loading.crossFadeFrom(binding.list)
            val adapter = binding.list.adapter as AppsAdapter
            try {
                withContext(Dispatchers.IO) { adapter.reload() }
            } finally {
                // Only now is `apps` populated; auto-select before this point
                // would persist an empty selection (M-9).
                binding.autoSelectProxyApps.isEnabled = true
            }
            adapter.filter.filter(binding.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupProxyMode()
        initProxiedUids()
        setupAppList()
        setupSearch()

        instance = this
        loadApps()
    }

    private fun setupToolbar() {
        binding.appPlaceholder.openSettings.setOnClickListener {
            openAppDetails()
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
    }

    private fun setupProxyMode() {
        if (!DataStore.proxyApps) {
            DataStore.proxyApps = true
        }

        binding.bypassGroup.check(if (DataStore.bypass) R.id.appProxyModeBypass else R.id.appProxyModeOn)
        binding.bypassGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.appProxyModeDisable -> {
                    DataStore.proxyApps = false
                    finish()
                }

                R.id.appProxyModeOn -> DataStore.bypass = false
                R.id.appProxyModeBypass -> DataStore.bypass = true
            }
        }
        // Disabled until loadApps() finishes: tapping auto-select while `apps`
        // is still empty would wipe the stored selection (M-9).
        binding.autoSelectProxyApps.isEnabled = false
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApp() }
    }

    private fun setupAppList() {
        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    private fun setupSearch() {
        binding.search.addTextChangedListener {
            applyFilter()
        }

        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            applyFilter()
        }
    }

    private fun openAppDetails() {
        val intent =
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                runOnDefaultDispatcher {
                    // Snapshot first: several apps can share one uid, and every
                    // row must flip against the state before the inversion.
                    val proxiedUidsOld = proxiedUids.clone()
                    for (app in apps) {
                        if (proxiedUidsOld.contains(app.uid)) {
                            proxiedUids.delete(app.uid)
                        } else {
                            proxiedUids[app.uid] = true
                        }
                    }
                    refreshIndividual()
                    apps = sortApps(apps)
                    onMainDispatcher {
                        applyFilter()
                    }
                }

                return true
            }

            R.id.action_clear_selections -> {
                runOnDefaultDispatcher {
                    proxiedUids.clear()
                    DataStore.individual = ""
                    apps = sortApps(apps)
                    onMainDispatcher {
                        applyFilter()
                    }
                }
            }

            R.id.action_export_clipboard -> {
                val success =
                    RiftApp.trySetPrimaryClip("${DataStore.bypass}\n${DataStore.individual}")
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                val proxiedAppString =
                    RiftApp.clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!proxiedAppString.isNullOrEmpty()) {
                    val i = proxiedAppString.indexOf('\n')
                    try {
                        val (enabled, apps) = if (i < 0) {
                            proxiedAppString to ""
                        } else proxiedAppString.substring(
                            0, i
                        ) to proxiedAppString.substring(i + 1)
                        binding.bypassGroup.check(if (enabled.toBoolean()) R.id.appProxyModeBypass else R.id.appProxyModeOn)
                        DataStore.individual = apps
                        Snackbar.make(
                            binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG
                        ).show()
                        initProxiedUids(apps)
                        // Re-sort like invert/clear do, so imported selections
                        // move to the top (L-11). `apps` here is the imported
                        // package list; the field is `this.apps`.
                        this.apps = sortApps(this.apps)
                        applyFilter()
                        return true
                    } catch (_: IllegalArgumentException) {
                    }
                }
                Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun selectProxyApp() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    applyAutoSelection()
                } catch (e: Exception) {
                    Logs.e(e)
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    /**
     * Picks the apps that need the proxy from the bundled list (plus uid 1000,
     * the system uid): in proxy mode those apps are selected, in bypass mode
     * every other app is selected instead.
     */
    private fun applyAutoSelection() {
        // M-9: 应用列表没加载出来（reload 抛错）时不要动已保存的选择，
        // 否则会把选择清空写回。
        if (apps.isEmpty()) return
        val needProxyAppsList = getAutoProxyApps("")
        val bypass = DataStore.bypass
        proxiedUids.clear()
        for (app in installedAppMap) {
            val needProxy =
                needProxyAppsList.contains(app.key) || (app.value.applicationInfo?.uid
                    ?: 0) == 1000
            if (needProxy) {
                if (!bypass) {
                    app.value.applicationInfo?.apply {
                        proxiedUids[uid] = true
                    }
                }
            } else {
                if (bypass) {
                    app.value.applicationInfo?.apply {
                        proxiedUids[uid] = true
                    }
                }
            }
        }
        refreshIndividual()
        apps = sortApps(apps)
        applyFilter()
    }

    private fun getAutoProxyApps(content: String): List<String> {
        var list = listOf<String>()
        try {
            val proxyApps = if (TextUtils.isEmpty(content)) {
                NGUtil.readTextFromAssets(app, "proxy_packagename.txt")
            } else {
                content
            }
            if (!TextUtils.isEmpty(proxyApps)) {
                list = proxyApps.split("\n")
            }
        } catch (_: Exception) {
        }
        return list
    }

    override fun supportNavigateUpTo(upIntent: Intent) =
        super.supportNavigateUpTo(upIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        instance = null
        loader?.cancel()
        super.onDestroy()
    }
}
