package light.rift.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import light.rift.R
import light.rift.RiftApp
import light.rift.bg.BaseService
import light.rift.database.DataStore
import light.rift.database.RiftDatabase
import light.rift.databinding.LayoutAssetItemBinding
import light.rift.databinding.LayoutAssetsBinding
import light.rift.ktx.*
import light.rift.widget.UndoSnackbarManager
import libcore.Libcore
import light.rift.core.utils.Util
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream

// Routing rule-sets (2026-10-31 migration): local .srs files, NOT the old
// geoip.db / geosite.db. Bundled files are extracted at install; this page
// is the manual update path (Harmony model, Android layout unchanged):
// each .srs shows its precise version "<date> <branch-sha>", updates are
// downloaded only when the user taps, verified before replacing, the
// replaced file is kept as a versioned backup, and the new file takes
// effect on the next connection — never silently, never automatically.
class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < assetNames.size) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                adapter.remove(index)
                undoManager.remove(index to (viewHolder as AssetHolder).file)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    // Bundled rule-sets, shown first and protected from swipe-delete.
    val assetNames = arrayOf("geoip-cn.srs", "geosite-cn.srs", "geosite-category-ads-all.srs")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            val rawName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }?.takeIf { it.isNotBlank() } ?: file.pathSegments.lastOrNull()
                ?.substringAfterLast('/')
                ?.substringAfter(':')
                .orEmpty()
            // A3: never let a display name carry a path — only the bare
            // file name may reach the assets directory.
            val fileName = rawName.substringAfterLast('/').substringAfterLast('\\')
            if (fileName.isBlank() || fileName.contains("..") || !fileName.endsWith(".srs")) {
                alert(getString(R.string.route_not_asset, rawName)).show()
                return@registerForActivityResult
            }
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val outFile = File(filesDir, fileName)
            val versionFile = File(
                filesDir, "${outFile.nameWithoutExtension}.version.txt"
            )

            // The actual install runs through the same verified,
            // backed-up, atomic path as an update (A3). A bundled file
            // that is not already Custom needs an explicit confirmation
            // before an import may overwrite it.
            fun installImported(tmpFile: File) {
                runOnDefaultDispatcher {
                    runCatching {
                        verifySrsFile(tmpFile)
                        backupCurrentFile(outFile, versionFile)
                        replaceSrsFile(tmpFile, outFile)
                        writeTextAtomic(versionFile, "Custom")
                    }.onSuccess {
                        onMainDispatcher { adapter.reloadAssets() }
                    }.onFailure {
                        tmpFile.delete()
                        onMainDispatcher { alert(it.readableMessage).tryToShow() }
                    }
                }
            }

            runOnDefaultDispatcher {
                val tmpFile = File(filesDir, "$fileName.tmp")
                try {
                    contentResolver.openInputStream(file)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open imported file")
                    verifySrsFile(tmpFile)
                } catch (e: Throwable) {
                    tmpFile.delete()
                    Logs.e(e)
                    onMainDispatcher { alert(e.readableMessage).tryToShow() }
                    return@runOnDefaultDispatcher
                }
                val currentVersion = try {
                    if (versionFile.isFile) versionFile.readText().trim() else ""
                } catch (_: Throwable) {
                    ""
                }
                val isBundled = fileName in assetNames
                if (isBundled && outFile.isFile && currentVersion != "Custom") {
                    onMainDispatcher {
                        RiftAlertDialogBuilder(this@AssetsActivity)
                            .setMessage(getString(R.string.route_import_overwrite, fileName))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                installImported(tmpFile)
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ ->
                                tmpFile.delete()
                                adapter.reloadAssets()
                            }
                            .create()
                            .tryToShow()
                    }
                } else {
                    installImported(tmpFile)
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
            R.id.action_update_all_srs -> {
                updateAllSrs()
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val files = filesDir.listFiles()
                ?.filter {
                    it.isFile && it.name.endsWith(".srs") && it.name !in assetNames &&
                        !it.name.contains(".bak-") && !it.name.endsWith(".bak") &&
                        !it.name.endsWith(".tmp")
                }
            assets.clear()
            assetNames.forEach { assets.add(File(filesDir, it)) }
            if (files != null) assets.addAll(files)

            layout.refreshLayout.post {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            for ((index, item) in actions) {
                assets.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val groups = actions.map { it.second }.toTypedArray()
            runOnDefaultDispatcher {
                // A3: never swipe-delete a .srs that stored rules still
                // reference — deleting it would break the next start
                // (the missing-file case in generateRuleSet). Blocked
                // files are left on disk and the list is reloaded so the
                // row comes back instead of silently disappearing.
                val referenced = referencedSrsNames()
                val blocked = ArrayList<String>()
                groups.forEach {
                    if (it.name in referenced) {
                        blocked.add(it.name)
                    } else {
                        it.deleteRecursively()
                        // Also drop the orphaned sidecar files, so a
                        // later same-name file does not resurrect stale
                        // version/checked/backup entries.
                        val dir = it.parentFile
                        if (dir != null) {
                            File(dir, "${it.nameWithoutExtension}.version.txt").delete()
                            File(dir, "${it.nameWithoutExtension}.checked.txt").delete()
                            dir.listFiles()
                                ?.filter { f -> f.name.startsWith(it.name + ".bak-") }
                                ?.forEach { f -> f.delete() }
                        }
                    }
                }
                onMainDispatcher {
                    adapter.reloadAssets()
                    if (blocked.isNotEmpty()) {
                        snackbar(
                            getString(R.string.route_delete_blocked, blocked.joinToString(", "))
                        ).show()
                    }
                }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage)
                        "<unknown>"
                    }
                } else {
                    "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                "<unknown>"
            }

            // Status shows the precise version plus staleness, never just a
            // coarse label: "Local version: 2026-10-31 · a1b2c3d".
            var status = getString(R.string.route_asset_status, displayVersion(localVersion))
            val checkedFile = File(file.parentFile, "${file.nameWithoutExtension}.checked.txt")
            var checkedDate: String? = null
            var checkedSha: String? = null
            if (checkedFile.isFile) {
                try {
                    val parts = checkedFile.readText().trim().split(" ")
                    if (parts.size >= 2) {
                        checkedDate = parts[0]
                        checkedSha = parts[1]
                    }
                } catch (_: Throwable) {
                }
            }
            if (checkedDate != null && checkedDate != versionDate(localVersion)) {
                status += " · checked $checkedDate"
            }
            if (isStale(localVersion, checkedDate, checkedSha)) {
                status += " · " + getString(R.string.route_stale_hint)
            }
            binding.assetStatus.text = status

            val isSrs = file.name.endsWith(".srs") &&
                (file.name.startsWith("geoip-") || file.name.startsWith("geosite-"))
            binding.rulesUpdate.isInvisible = !isSrs
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateSrsFile(file, versionFile)
                    }.onSuccess { newVersion ->
                        onMainDispatcher {
                            if (newVersion == null) {
                                snackbar(R.string.route_asset_no_update).show()
                            } else {
                                showTakesEffectDialog()
                            }
                        }
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        adapter.reloadAssets()
                        binding.rulesUpdate.isInvisible = false
                        binding.subscriptionUpdateProgress.isInvisible = true
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

            val backups = listBackups(file)
            binding.rulesRollback.isInvisible = backups.isEmpty()
            binding.rulesRollback.setOnClickListener {
                showRollbackDialog(file, versionFile, backups)
            }

        }

    }

    // Rule-assets providers, kept from the db era: index 0 is the official
    // RiftApp source; the others reuse the same rule-set branch layout, and
    // a missing file there fails the update safely with the old file kept.
    // Index 4 is Rift's own compiled table (the default, rulesProvider
    // == 4); its branch is "srs", the others use "rule-set".
    private val rulesProviders = listOf(
        RuleAssetsProvider("SagerNet/sing-geoip", "SagerNet/sing-geosite"),
        RuleAssetsProvider("soffchen/sing-geoip", "soffchen/sing-geosite"),
        RuleAssetsProvider("Chocolate4U/Iran-sing-box-rules"),
        RuleAssetsProvider("L11R/antizapret-sing-box-geo"),
        RuleAssetsProvider("riftglow/rift-rules", "riftglow/rift-rules", "srs"),
    )

    private fun srsRepoFor(fileName: String): String {
        val provider = rulesProviders.getOrNull(DataStore.rulesProvider)
            ?: rulesProviders[0]
        return if (fileName.startsWith("geoip-")) provider.geoipRepo else provider.geositeRepo
    }

    private fun srsBranchFor(fileName: String): String {
        val provider = rulesProviders.getOrNull(DataStore.rulesProvider)
            ?: rulesProviders[0]
        return provider.branch
    }

    // Version helpers. A version string is "<YYYY-MM-DD> <short-sha>"
    // (or "Custom" / "<unknown>" for imported / missing files).
    private fun versionDate(version: String): String? {
        val first = version.trim().split(" ").firstOrNull() ?: return null
        return if (first.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) first else null
    }

    private fun versionSha(version: String): String? {
        val parts = version.trim().split(" ")
        return if (parts.size >= 2) parts[1] else null
    }

    private fun displayVersion(version: String): String {
        val date = versionDate(version)
        val sha = versionSha(version)
        return if (date != null && sha != null) "$date · $sha" else version
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun parseDate(date: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date)
    } catch (_: Exception) {
        null
    }

    // Stale = the rule-set itself is older than 30 days. A recent check
    // that confirmed this exact version is the latest suppresses the hint
    // for 7 days, so a checked-and-current file does not nag. Nothing here
    // downloads or replaces anything on its own.
    private fun isStale(version: String, checkedDate: String?, checkedSha: String?): Boolean {
        val date = versionDate(version)?.let(::parseDate) ?: return false
        val ageDays = (System.currentTimeMillis() - date.time) / 86_400_000L
        if (ageDays <= 30) return false
        if (checkedDate != null && checkedSha != null && checkedSha == versionSha(version)) {
            val checked = parseDate(checkedDate) ?: return true
            val checkedAgeDays = (System.currentTimeMillis() - checked.time) / 86_400_000L
            if (checkedAgeDays <= 7) return false
        }
        return true
    }

    private fun listBackups(file: File): List<File> {
        val dir = file.parentFile ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(file.name + ".bak-") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    // Backup filename "<name>.bak-<YYYY-MM-DD>-<sha>" -> display
    // "<YYYY-MM-DD> · <sha>"; "unknown" stays "unknown".
    private fun backupDisplayName(backup: File, file: File): String {
        val rest = backup.name.removePrefix(file.name + ".bak-")
        return if (rest.length > 11 && rest[10] == '-') {
            "${rest.substring(0, 10)} · ${rest.substring(11)}"
        } else {
            rest
        }
    }

    private fun backupVersionString(backup: File, file: File): String {
        val rest = backup.name.removePrefix(file.name + ".bak-")
        return if (rest.length > 11 && rest[10] == '-') {
            "${rest.substring(0, 10)} ${rest.substring(11)}"
        } else {
            rest
        }
    }

    private fun newHttpClient() = Libcore.newHttpClient().apply {
        modernTLS()
        keepAlive()
        // 本地代理认证（0.2.29 -26）：mixed 入口开了 users 就得带账号密码。
        if (DataStore.localProxyAuthEnabled) {
            trySocks5Auth(DataStore.mixedPort, DataStore.mixedInboundUser, DataStore.mixedInboundPass)
        } else {
            trySocks5(DataStore.mixedPort)
        }
    }

    // Branch HEAD commit (short SHA) for a repo's rule-set branch, via the
    // GitHub API. Best effort: if the API fails or rate-limits, the
    // caller still downloads the file with sha "unknown" rather than
    // failing the whole update — the .srs download itself is the hard gate.
    private suspend fun branchHeadSha(repo: String, branch: String): String {
        return try {
            val client = newHttpClient()
            try {
                val response = client.newRequest().apply {
                    setURL("https://api.github.com/repos/$repo/commits?sha=$branch&per_page=1")
                }.execute()
                val commits = JSONArray(Util.getStringBox(response.contentString))
                if (commits.length() > 0) {
                    commits.getJSONObject(0).optString("sha").take(7)
                } else {
                    "unknown"
                }
            } finally {
                client.close()
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    // Verifies a .srs file. Level implemented (A4): header magic "SRS"
    // plus a supported version byte (1..5), followed by a full inflate
    // of the remaining bytes to EOF. In sing-box's binary format the
    // bytes after the 4-byte header are a zlib stream (see
    // sing-box/common/srs Read: magic + version, then zlib), so
    // inflating catches truncation and corruption that a header-only
    // check cannot — a truncated download still starts with the right
    // 4 bytes. This is a structural integrity check, NOT a full
    // rule-item parse: Kotlin has no srs.Read binding to call, and the
    // kernel does the full srs.Read parse at the next start, with the
    // versioned backup as fallback. A file that passes here but fails
    // there is therefore still caught before it can route traffic.
    private fun verifySrsFile(tmp: File) {
        if (!tmp.isFile || tmp.length() <= 4) {
            error("Downloaded rule-set is empty or too small: ${tmp.name}")
        }
        FileInputStream(tmp).use { input ->
            val header = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = input.read(header, off, 4 - off)
                if (n < 0) error("Downloaded rule-set is truncated: ${tmp.name}")
                off += n
            }
            if (!(header[0] == 0x53.toByte() && header[1] == 0x52.toByte() &&
                    header[2] == 0x53.toByte() && header[3].toInt() in 1..5)
            ) {
                error("Downloaded file is not a valid sing-box rule-set (.srs)")
            }
            var decompressed = 0L
            InflaterInputStream(input).use { inflater ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = inflater.read(buf)
                    if (n < 0) break
                    decompressed += n
                    if (decompressed > 64L * 1024 * 1024) {
                        error("Downloaded rule-set is unexpectedly large: ${tmp.name}")
                    }
                }
            }
            if (decompressed == 0L) {
                error("Downloaded rule-set has an empty payload: ${tmp.name}")
            }
        }
    }

    // Writes text via tmp+rename, never a direct in-place overwrite, so
    // a crash mid-write cannot leave a half-written version file (B10
    // pattern, applied to Kotlin-side version files too).
    private fun writeTextAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Failed to write ${file.name}")
        }
    }

    // Versioned backup of the file being replaced (never a single .bak
    // that the next update overwrites). B6: a current file that fails
    // verification is NOT added to the backup chain — a damaged backup
    // is worse than no backup, it only wastes a slot the fallback
    // would have to parse and skip.
    private fun backupCurrentFile(file: File, versionFile: File) {
        if (!file.isFile || file.length() == 0L) return
        try {
            verifySrsFile(file)
        } catch (_: Throwable) {
            return
        }
        val localVersion = try {
            if (versionFile.isFile) versionFile.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }
        val oldKey = if (localVersion.isNotBlank()) {
            localVersion.replace(" ", "-")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
        } else {
            "unknown-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
                .format(Date(file.lastModified()))
        }
        val backup = File(file.parentFile, "${file.name}.bak-$oldKey")
        if (!backup.isFile) {
            // S-B10: stage the backup through a tmp file and rename
            // it into place — a crash mid-copy must not leave a
            // half-written .bak occupying the slot (a rollback
            // would then have to parse and reject it). The tmp name
            // deliberately does NOT start with "<name>.bak-", so a
            // leftover tmp can never show up in the rollback list.
            val tmp = File(file.parentFile, "${file.name}.bak.tmp")
            try {
                file.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(backup)) {
                    tmp.delete()
                    error("Failed to back up ${file.name}")
                }
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }
        pruneBackups(file)
    }

    // S-B10: backups are kept per version; prune to the newest 3
    // so repeated updates cannot pile up .bak files forever.
    // Names sort chronologically ("<name>.bak-<date>-<sha>").
    private fun pruneBackups(file: File) {
        val dir = file.parentFile ?: return
        val backups = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(file.name + ".bak-") }
            ?.sortedByDescending { it.name }
            ?: return
        backups.drop(3).forEach { it.delete() }
    }

    // A4: replacement is uniformly tmp+rename. There is deliberately no
    // copyTo-overwrite fallback: a failed rename must fail the update
    // (tmp deleted, original untouched), not silently leave a file that
    // may have been half-copied over the good one.
    private fun replaceSrsFile(tmpFile: File, target: File) {
        if (!tmpFile.renameTo(target)) {
            tmpFile.delete()
            error("Failed to install rule-set ${target.name}")
        }
    }

    // .srs file names referenced by stored rules (domains + ip fields,
    // "private" excluded — it has no file). Used before swipe-delete.
    // On a DB read failure the set is empty and deletion proceeds as
    // before; failing open here must not trap the user on this page.
    private fun referencedSrsNames(): Set<String> {
        return try {
            val names = HashSet<String>()
            for (rule in RiftDatabase.rulesDao.allRules()) {
                for (field in listOf(rule.domains, rule.ip)) {
                    if (field.isNullOrBlank()) continue
                    for (entry in field.split(",", "\n")) {
                        val item = entry.trim()
                        when {
                            item.startsWith("geosite:") -> {
                                val tag = item.removePrefix("geosite:")
                                if (tag.isNotBlank() && tag != "private") {
                                    names.add("geosite-$tag.srs")
                                }
                            }
                            item.startsWith("geoip:") -> {
                                val tag = item.removePrefix("geoip:")
                                if (tag.isNotBlank() && tag != "private") {
                                    names.add("geoip-$tag.srs")
                                }
                            }
                        }
                    }
                }
            }
            names
        } catch (e: Throwable) {
            Logs.e(e)
            emptySet()
        }
    }

    // Downloads one .srs file and, only after verification, replaces the
    // current file (keeping it as a versioned backup first).
    // Returns the new version string, or null when already on the latest
    // branch commit (in which case only the checked date is recorded).
    // Any failure throws before the current file is touched.
    private suspend fun updateSrsFile(file: File, versionFile: File): String? {
        val fileName = file.name
        val repo = srsRepoFor(fileName)
        val branch = srsBranchFor(fileName)
        val localVersion = if (versionFile.isFile) {
            try { versionFile.readText().trim() } catch (_: Throwable) { "" }
        } else {
            ""
        }
        val localSha = versionSha(localVersion)

        val branchSha = branchHeadSha(repo, branch)
        if (branchSha != "unknown" && localSha != null && branchSha == localSha) {
            val checkedFile = File(file.parentFile, "${file.nameWithoutExtension}.checked.txt")
            try {
                writeTextAtomic(checkedFile, "${todayString()} $branchSha")
            } catch (_: Throwable) {
            }
            return null
        }

        val client = newHttpClient()
        try {
            val response = client.newRequest().apply {
                setURL("https://raw.githubusercontent.com/$repo/$branch/$fileName")
            }.execute()

            val tmpFile = File(file.parentFile, file.name + ".tmp")
            tmpFile.parentFile?.mkdirs()
            // A4: in gomobile bindings a Go error return surfaces as a
            // Kotlin exception, so writeTo is "checked" by catching it;
            // on top of that, an expected Content-Length (when the
            // server sends one) is compared with the bytes actually
            // written, so a silently short body cannot pass either.
            // Any failure deletes the tmp file and leaves the original
            // untouched.
            try {
                response.writeTo(tmpFile.canonicalPath)
                val expected = try {
                    Util.getStringBox(response.getHeader("Content-Length"))
                        ?.toLongOrNull()
                } catch (_: Throwable) {
                    null
                }
                if (expected != null && expected >= 0 && tmpFile.length() != expected) {
                    error("Downloaded rule-set size mismatch: expected $expected bytes, got ${tmpFile.length()}")
                }
                verifySrsFile(tmpFile)
            } catch (e: Throwable) {
                tmpFile.delete()
                throw e
            }

            backupCurrentFile(file, versionFile)
            replaceSrsFile(tmpFile, file)

            // S-B9: an unknown branch SHA must never overwrite a
            // known version — the download itself succeeded, but
            // stamping it "unknown" would destroy the last good
            // version record (and a "Custom" marker). Leave the
            // version file untouched; the return value still reports
            // the update, and the next update re-checks the SHA.
            if (branchSha != "unknown") {
                val newVersion = "${todayString()} $branchSha"
                writeTextAtomic(versionFile, newVersion)
                return newVersion
            }
            return "${todayString()} $branchSha"
        } finally {
            client.close()
        }
    }

    private fun showRollbackDialog(file: File, versionFile: File, backups: List<File>) {
        val labels = backups.map { backupDisplayName(it, file) }.toTypedArray()
        RiftAlertDialogBuilder(this)
            .setTitle(R.string.route_rollback_title)
            .setItems(labels) { _, which ->
                val backup = backups[which]
                runOnDefaultDispatcher {
                    runCatching {
                        val tmpFile = File(file.parentFile, file.name + ".tmp")
                        backup.copyTo(tmpFile, overwrite = true)
                        try {
                            verifySrsFile(tmpFile)
                        } catch (e: Throwable) {
                            tmpFile.delete()
                            throw e
                        }
                        // Keep the file being rolled back from, if it
                        // parses, so a rollback never permanently loses
                        // an imported Custom file (which import never
                        // backed up on its own).
                        backupCurrentFile(file, versionFile)
                        replaceSrsFile(tmpFile, file)
                        // The backup itself is kept, so this rollback can
                        // be undone by rolling back again to another version.
                        writeTextAtomic(versionFile, backupVersionString(backup, file))
                    }.onSuccess {
                        onMainDispatcher {
                            adapter.reloadAssets()
                            showTakesEffectDialog()
                        }
                    }.onFailure {
                        onMainDispatcher {
                            alert(it.readableMessage).tryToShow()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.route_later, null)
            .create()
            .tryToShow()
    }

    // Plain statement, not small print: the update is on disk and the
    // running core watches local .srs files (sing-box LocalRuleSet
    // fswatch) and reloads them for new connections, so no reconnect
    // is required. The reconnect button is still offered: it applies
    // the new rules to existing connections too; "Later" leaves the
    // current connection alone.
    private fun showTakesEffectDialog() {
        RiftAlertDialogBuilder(this)
            .setMessage(R.string.route_rules_updated)
            .setPositiveButton(R.string.route_reconnect_now) { _, _ ->
                reconnect()
            }
            .setNegativeButton(R.string.route_later, null)
            .create()
            .tryToShow()
    }

    private fun reconnect() {
        runOnDefaultDispatcher {
            if (DataStore.serviceState == BaseService.State.Stopped ||
                DataStore.serviceState == BaseService.State.Idle
            ) {
                RiftApp.startService()
                return@runOnDefaultDispatcher
            }
            RiftApp.stopService()
            var waited = 0
            while (DataStore.serviceState != BaseService.State.Stopped && waited < 10_000) {
                delay(500)
                waited += 500
            }
            RiftApp.startService()
        }
    }

    // Updates every rule-set actually in use: the bundled three plus any
    // code referenced by the stored rules (e.g. a user-added country).
    // Missing files are created by the download, so a rule whose .srs was
    // never bundled can be fixed from here. Takes effect on next
    // connection; one dialog at the end covers the whole batch.
    private fun updateAllSrs() {
        updating.incrementAndGet()
        layout.refreshLayout.isEnabled = false
        runOnDefaultDispatcher {
            val filesDir = getExternalFilesDir(null) ?: filesDir
            val targets = LinkedHashSet<File>()
            assetNames.forEach { targets.add(File(filesDir, it)) }
            try {
                val rules = RiftDatabase.rulesDao.allRules()
                for (rule in rules) {
                    for (field in listOf(rule.domains, rule.ip)) {
                        if (field.isNullOrBlank()) continue
                        for (entry in field.split(",", "\n")) {
                            val item = entry.trim()
                            if (item.startsWith("geosite:")) {
                                val tag = item.removePrefix("geosite:")
                                if (tag.isNotBlank() && tag != "private") {
                                    targets.add(File(filesDir, "geosite-$tag.srs"))
                                }
                            } else if (item.startsWith("geoip:")) {
                                val tag = item.removePrefix("geoip:")
                                if (tag.isNotBlank() && tag != "private") {
                                    targets.add(File(filesDir, "geoip-$tag.srs"))
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // DB read failure must not block updating the bundled sets.
                Logs.e(e)
            }

            val failed = ArrayList<String>()
            var updated = 0
            for (target in targets) {
                val versionFile = File(
                    target.parentFile,
                    "${target.nameWithoutExtension}.version.txt"
                )
                try {
                    if (updateSrsFile(target, versionFile) != null) updated++
                } catch (e: Throwable) {
                    failed.add(target.name)
                    Logs.e(e)
                }
            }

            onMainDispatcher {
                adapter.reloadAssets()
                layout.refreshLayout.isEnabled = true
                updating.set(0)
                when {
                    updated == 0 && failed.isNotEmpty() -> {
                        alert(getString(R.string.route_update_failed, failed.joinToString(", "))).tryToShow()
                    }
                    updated > 0 -> {
                        if (failed.isNotEmpty()) {
                            snackbar(getString(R.string.route_update_partial, failed.joinToString(", "))).show()
                        }
                        showTakesEffectDialog()
                    }
                    else -> {
                        snackbar(R.string.route_asset_no_update).show()
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

    private data class RuleAssetsProvider(
        val geoipRepo: String,
        val geositeRepo: String = geoipRepo,
        val branch: String = "rule-set",
    )
}
