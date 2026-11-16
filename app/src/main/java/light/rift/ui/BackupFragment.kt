package light.rift.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import light.rift.BuildConfig
import light.rift.Key
import light.rift.R
import light.rift.RiftApp
import light.rift.bg.Executable
import light.rift.database.*
import light.rift.database.preference.KeyValuePair
import light.rift.database.preference.PublicDatabase
import light.rift.databinding.LayoutBackupBinding
import light.rift.databinding.LayoutImportBinding
import light.rift.databinding.LayoutProgressBinding
import light.rift.ktx.*
import kotlinx.coroutines.delay
import light.rift.core.utils.Util
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    // True only when a stored WebDAV ciphertext exists but could not be
    // decrypted at page load. While true and the password field is still
    // empty, readAndSaveWebdav() must refuse to save: the empty field is
    // "we could not read it", not "the user cleared it", and saving it
    // would permanently overwrite the stored ciphertext with "".
    private var webdavPasswordDecryptFailed = false

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(content)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        // 本地卡（设计图首屏）：只露两行——“备份到文件 >”点开是勾选 +
        // 导出/分享，“从文件恢复 >”直接选文件导入。细节藏在入口后面。
        binding.localContent.isVisible = false
        binding.localBackupRow.setOnClickListener {
            binding.localContent.isVisible = !binding.localContent.isVisible
        }
        binding.localRestoreRow.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        // Keyboard: on Android 15 edge-to-edge is enforced, so the manifest
        // adjustResize alone no longer shrinks this window (verified on the
        // user's Xiaomi 15: #14 still left the download button under the
        // keyboard). The ScrollView must therefore consume the IME inset
        // itself: paddingBottom = keyboard height gives the page room to
        // scroll the buttons above the keyboard.
        val scrollView = view as ScrollView
        var lastKeyboardPadding = -1
        fun applyKeyboardPadding(height: Int) {
            if (height != lastKeyboardPadding) {
                lastKeyboardPadding = height
                scrollView.updatePadding(bottom = height)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyKeyboardPadding(maxOf(imeBottom, navBottom))
            insets
        }
        // Fallback in case insets do not reach this fragment through
        // ViewPager2/CoordinatorLayout: measure the keyboard directly from
        // the decor view's visible frame. Only applies when a real keyboard
        // is up (>15% of the screen), so it cannot fight the insets path
        // when the keyboard is hidden.
        val decorView = requireActivity().window.decorView
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            decorView.getWindowVisibleDisplayFrame(rect)
            val keypadHeight = (decorView.height - rect.bottom).coerceAtLeast(0)
            if (keypadHeight > decorView.height * 0.15) {
                applyKeyboardPadding(keypadHeight)
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            }
        })
        // When a WebDAV field gains focus, scroll it above the keyboard
        // once the keyboard (and the padding above) is in place. The
        // field's rect is converted to ScrollView coordinates, because
        // the field now sits inside the collapsible WebDAV container,
        // not directly under the ScrollView's child.
        fun scrollFieldIntoView(field: View) {
            scrollView.postDelayed({
                val rect = Rect()
                field.getDrawingRect(rect)
                scrollView.offsetDescendantRectToMyCoords(field, rect)
                val visibleHeight = scrollView.height - scrollView.paddingBottom
                val target = (rect.bottom - visibleHeight).coerceAtLeast(0)
                if (target > scrollView.scrollY) {
                    scrollView.smoothScrollTo(0, target)
                }
            }, 300)
        }

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        // 导出内容（2026-11-10，规划第 5 项）：完整 / 匿名，只影响导出与
        // 分享；WebDAV 上传与自动备份永远用完整档。本次打开页面内有效，
        // 默认完整，不存盘。
        var exportAnonymous = false
        binding.exportTierGroup.setOnCheckedChangeListener { _, checkedId ->
            exportAnonymous = checkedId == R.id.export_tier_anonymous
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked,
                    exportAnonymous
                )
                onMainDispatcher {
                    val name = if (exportAnonymous) {
                        "rift_backup_anonymous_${Date().toLocaleString()}.json"
                    } else {
                        "rift_backup_${Date().toLocaleString()}.json"
                    }
                    startFilesForResult(exportSettings, name)
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked,
                    exportAnonymous
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, if (exportAnonymous) {
                        "rift_backup_anonymous_${Date().toLocaleString()}.json"
                    } else {
                        "rift_backup_${Date().toLocaleString()}.json"
                    }
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        // （本地“从文件恢复 >”行已接管选文件导入，见 local_restore_row。）

        // WebDAV backup (manual only, Nutstore by default)
        // Like Legado: the server field takes the bare root (/dav/),
        // Rift creates and uses the Rift subfolder itself. Older builds
        // stored the full .../dav/Rift/ address; normalize it back to
        // the root so the field matches what other apps show, without
        // changing where the file actually lives.
        if (DataStore.webdavServer.trimEnd('/') == WebDavBackup.LEGACY_FOLDER_SERVER.trimEnd('/')) {
            DataStore.webdavServer = WebDavBackup.DEFAULT_SERVER
        }
        binding.webdavServer.setText(DataStore.webdavServer)
        binding.webdavUsername.setText(DataStore.webdavUsername)
        val webdavDecrypted =
            WebDavBackup.decryptPassword(DataStore.webdavPasswordEncrypted)
        webdavPasswordDecryptFailed =
            DataStore.webdavPasswordEncrypted.isNotBlank() && webdavDecrypted == null
        if (webdavDecrypted != null) {
            binding.webdavPassword.setText(webdavDecrypted)
        }
        // 远程卡（设计图首屏）：标题“远程备份”固定；副标题按服务器显示
        // “WebDAV · 坚果云”或通用 WebDAV。圆点 + 状态；备份文件；
        // 立即备份（琥珀按钮 + 上次状态）；从云端恢复 >。
        // 点“编辑”才展开字段。
        val webdavConfigured = DataStore.webdavUsername.isNotBlank() &&
            !webdavDecrypted.isNullOrBlank()
        // 副标题：坚果云（默认服务器）时显示“WebDAV · 坚果云”，否则通用。
        val serverHost = runCatching {
            Uri.parse(DataStore.webdavServer).host
        }.getOrNull().orEmpty()
        binding.webdavSubtitle.text = if (serverHost.contains("jianguoyun")) {
            getString(R.string.backup_remote_subtitle)
        } else {
            getString(R.string.webdav_title)
        }
        binding.webdavFileNameValue.text = WebDavBackup.FILE_NAME
        // “最近备份”两条（选项卡，设计图）：自动一条、手动一条。
        // 放在 updateWebdavLastBackup 之前定义——Kotlin 局部函数
        // 只能在声明点之后调用，updateWebdavLastBackup 里要调它。
        fun updateRecentBackups() {
            val autoTime = DataStore.autoBackupLastTime
            binding.recentBackupAuto.text = when {
                autoTime <= 0 -> getString(R.string.auto_backup_never)
                DataStore.autoBackupLastResult == "success" ->
                    getString(
                        R.string.auto_backup_last_success,
                        formatAutoBackupTime(autoTime)
                    )
                else -> getString(
                    R.string.auto_backup_last_failed,
                    formatAutoBackupTime(autoTime)
                )
            }
            val manualTime = DataStore.webdavLastUploadTime
            binding.recentBackupManual.text = when {
                manualTime <= 0 -> getString(R.string.webdav_last_backup_never)
                DataStore.webdavLastUploadResult == "success" ->
                    getString(
                        R.string.webdav_last_backup_success,
                        formatAutoBackupTime(manualTime)
                    )
                else -> getString(
                    R.string.webdav_last_backup_failed,
                    formatAutoBackupTime(manualTime)
                )
            }
            // autoBackupStatus 仍保留（gone），供旧逻辑复用；首屏看
            // 最近备份两条就够了。
            binding.autoBackupStatus.text = binding.recentBackupAuto.text
        }
        // “上次”行（设计图）：“成功”绿字、“失败”红字；分隔用“·”。
        // 颜色只上在结果词上——用“·”定位，中文“成功/失败”与英文
        // “OK/failed”都适用；没“·”就原样返回，不崩。
        fun lastBackupText(time: Long, result: String): CharSequence {
            if (time <= 0) return getString(R.string.webdav_last_backup_never)
            val ok = result == "success"
            val raw = getString(
                if (ok) R.string.webdav_last_backup_success
                else R.string.webdav_last_backup_failed,
                formatAutoBackupTime(time)
            )
            val start = raw.lastIndexOf('·') + 2
            if (start <= 1 || start >= raw.length) return raw
            return SpannableString(raw).apply {
                setSpan(
                    ForegroundColorSpan(
                        requireContext().getColor(
                            if (ok) R.color.color_rift_success
                            else R.color.color_rift_danger
                        )
                    ),
                    start, raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        // “立即备份”行右端：最近一次 WebDAV 备份（手动或自动取较新的）。
        // 同时刷新选项卡的“最近备份”两条（自动一条、手动一条）。
        fun updateWebdavLastBackup() {
            val manualTime = DataStore.webdavLastUploadTime
            val autoTime = DataStore.autoBackupLastTime
            val (time, result) = if (manualTime >= autoTime) {
                manualTime to DataStore.webdavLastUploadResult
            } else {
                autoTime to DataStore.autoBackupLastResult
            }
            binding.webdavLastBackup.text = lastBackupText(time, result)
            updateRecentBackups()
        }
        updateWebdavLastBackup()
        // 状态行（设计图）：状态词在左、域名在右，分两栏；域名为空时右栏隐藏。
        var webdavProbeResult: Boolean? = null
        fun updateWebdavStatus() {
            binding.webdavHost.text = serverHost
            binding.webdavHost.isVisible = serverHost.isNotBlank()
            binding.webdavStatus.text = when {
                webdavPasswordDecryptFailed -> getString(R.string.webdav_decrypt_failed)
                !webdavConfigured -> getString(R.string.webdav_bind)
                webdavProbeResult == null -> getString(R.string.webdav_configured)
                webdavProbeResult == true -> getString(R.string.webdav_probe_ok)
                else -> getString(R.string.webdav_probe_failed)
            }
        }
        updateWebdavStatus()
        // WebDAV connectivity dot: probe once on page load when an
        // account is configured (so the first glance already shows it).
        // Gray = not probed, green = reachable, red = failed. A failed
        // probe never blocks the manual rows below it.
        var webdavProbed = false
        fun probeWebdav() {
            if (webdavProbed || !webdavConfigured) return
            webdavProbed = true
            binding.webdavStatus.text = getString(R.string.webdav_probe_checking)
            val server = binding.webdavServer.text.toString().trim()
            val username = binding.webdavUsername.text.toString().trim()
            val password = binding.webdavPassword.text.toString()
            if (username.isBlank() || password.isBlank()) return
            runOnDefaultDispatcher {
                val ok = runCatching {
                    WebDavBackup.probe(server, username, password)
                }.getOrDefault(false)
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    webdavProbeResult = ok
                    binding.webdavDot.setTextColor(
                        requireContext().getColor(
                            if (ok) R.color.color_rift_success
                            else R.color.color_rift_danger
                        )
                    )
                    updateWebdavStatus()
                }
            }
        }
        probeWebdav()
        // “编辑”打开抽屉：服务器/账号/密码字段。未配置时它就是绑定入口。
        binding.webdavContent.isVisible = false
        binding.webdavEditButton.setOnClickListener {
            binding.webdavContent.isVisible = !binding.webdavContent.isVisible
        }
        listOf(
            binding.webdavServer, binding.webdavUsername, binding.webdavPassword
        ).forEach { field ->
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) scrollFieldIntoView(field)
            }
        }

        // 立即备份（设计图：琥珀按钮）。点之前先保存编辑框里的账号密码
        // （若用户刚改过），再上传。上传后刷新“上次”与最近备份。
        binding.webdavUploadButton.setOnClickListener {
            val saved = readAndSaveWebdav(binding) ?: run {
                snackbar(getString(R.string.webdav_decrypt_failed)).show()
                return@setOnClickListener
            }
            val (server, username, password) = saved
            val backupProfile = binding.backupConfigurations.isChecked
            val backupRule = binding.backupRules.isChecked
            val backupSetting = binding.backupSettings.isChecked
            runOnDefaultDispatcher {
                try {
                    val backup = doBackup(backupProfile, backupRule, backupSetting)
                    WebDavBackup.upload(server, username, password, backup)
                    DataStore.webdavLastUploadTime = System.currentTimeMillis()
                    DataStore.webdavLastUploadResult = "success"
                    onMainDispatcher {
                        webdavProbeResult = true
                        binding.webdavDot.setTextColor(
                            requireContext().getColor(R.color.color_rift_success)
                        )
                        updateWebdavStatus()
                        updateWebdavLastBackup()
                        snackbar(getString(R.string.webdav_upload_success)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    DataStore.webdavLastUploadTime = System.currentTimeMillis()
                    DataStore.webdavLastUploadResult = "failed"
                    onMainDispatcher {
                        updateWebdavLastBackup()
                        snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        binding.webdavRestoreRow.setOnClickListener {
            val saved = readAndSaveWebdav(binding) ?: run {
                snackbar(getString(R.string.webdav_decrypt_failed)).show()
                return@setOnClickListener
            }
            val (server, username, password) = saved
            // A9: the download leads into the import dialog, so it
            // shares the import's lifecycle scope.
            runOnLifecycleDispatcher {
                try {
                    val text = WebDavBackup.download(server, username, password)
                    importFromJsonText(text)
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded) snackbar(e.readableMessage).show()
                    }
                }
            }
        }

        // ---- Options card（设计图）：自动备份开关 + 一行说明；保留策略；
        // 最近备份两条（自动一条、手动一条）。恢复策略（覆盖/合并）
        // 不在此卡，改到导入对话框里选（恢复时才 relevant）。----
        // updateRecentBackups 定义在前面（updateWebdavLastBackup 之前），
        // 这里直接调。
        binding.autoBackupSwitch.isChecked = DataStore.autoBackupEnabled
        binding.autoBackupSwitch.setOnCheckedChangeListener { _, checked ->
            DataStore.autoBackupEnabled = checked
            if (!checked) AutoBackup.cancel()
        }
        updateRecentBackups()
    }

    private fun formatAutoBackupTime(timeMillis: Long): String {
        // 设计图只写时间（上次 06:20）；当天内只显示 HH:mm，
        // 跨天才带日期，避免“昨天几点”说不清。
        val pattern = if (DateUtils.isToday(timeMillis)) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault())
            .format(Date(timeMillis))
    }

    /**
     * Saves the current WebDAV fields and returns them, or null when
     * saving must not happen. Null happens in two cases, both of which
     * must leave the stored ciphertext untouched: (1) decryption failed
     * at load and the user has not typed a new password yet, so the
     * empty field is not an intentional clear; (2) encryption itself
     * failed, so there is no safe ciphertext to store.
     */
    private fun readAndSaveWebdav(binding: LayoutBackupBinding): Triple<String, String, String>? {
        val server = binding.webdavServer.text.toString().trim()
            .ifEmpty { WebDavBackup.DEFAULT_SERVER }
        val username = binding.webdavUsername.text.toString().trim()
        val password = binding.webdavPassword.text.toString()
        if (webdavPasswordDecryptFailed && password.isEmpty()) {
            return null
        }
        // 先校验、后存盘：空账号/空密码误点上传时，旧的有效配置
        // 绝不能被空值覆盖。校验失败仍返回 Triple，让调用方走
        // WebDavBackup 的 require 报错并提示用户，但不写盘。
        if (username.isBlank() || password.isBlank()) {
            return Triple(server, username, password)
        }
        val encrypted = try {
            WebDavBackup.encryptPassword(password)
        } catch (e: Exception) {
            Logs.w(e)
            return null
        }
        webdavPasswordDecryptFailed = false
        DataStore.webdavServer = server
        DataStore.webdavUsername = username
        DataStore.webdavPasswordEncrypted = encrypted
        return Triple(server, username, password)
    }

    // Backup content lives in BackupContent so the auto-backup worker
    // builds byte-identical files without a UI. Same sections, same order.
    // anonymous (plan item 5) is export/share only; WebDAV upload and
    // auto backup call this without it and always get the full backup.
    fun doBackup(
        profile: Boolean, rule: Boolean, setting: Boolean,
        anonymous: Boolean = false
    ): String {
        return BackupContent.build(profile, rule, setting, anonymous)
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            // A9: import callbacks run in the fragment's lifecycle
            // scope, not GlobalScope, so a destroyed page cannot be
            // hit by requireContext() in a late callback.
            runOnLifecycleDispatcher {
                try {
                    startImport(file)
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        try {
                            if (isAdded) snackbar(e.readableMessage).show()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val resolver = context?.contentResolver ?: return
        val fileName = try {
            resolver.query(file, null, null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx < 0) null else cursor.getString(idx)
                }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logs.w(e)
            null
        } ?: file.pathSegments.lastOrNull()
            ?.substringAfterLast('/')
            ?.substringAfter(':')
            .orEmpty()

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            if (isAdded) snackbar(getString(R.string.invalid_backup_file)).show()
        }

        val content = try {
            JSONObject((resolver.openInputStream(file) ?: run {
                onMainDispatcher {
                    if (isAdded) snackbar(getString(R.string.invalid_backup_file)).show()
                }
                return
            }).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        showImportDialog(content)
    }

    /** WebDAV download path: same import dialog as a local file, minus the .json name check. */
    suspend fun importFromJsonText(text: String) {
        suspend fun invalid() = onMainDispatcher {
            if (isAdded) snackbar(getString(R.string.invalid_backup_file)).show()
        }
        val content = try {
            JSONObject(text)
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }
        showImportDialog(content)
    }

    // Set by finishImport when a later transaction fails after an
    // earlier one already committed (A10). Two Room databases cannot
    // share one transaction, so a part-way failure is reported as
    // "partially applied" instead of a generic failure.
    private var importPartiallyApplied = false

    private suspend fun showImportDialog(content: JSONObject) {
        onMainDispatcher {
            // A9: no dialog, no requireContext(), on a dead page.
            if (!isAdded) return@onMainDispatcher
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            // 恢复范围（2026-11-10，规划第 2 项）：先二选一，再由勾选微调。
            // 仅恢复配置 = 节点、分组与规则，不动应用设置；恢复全部数据 =
            // 再加设置。没有设置段可排时，范围选择没有意义，整组隐藏。
            // 单选只是预设勾选的快捷方式，不与勾选双向同步。
            val hasProfiles = content.has("profiles")
            val hasRules = content.has("rules")
            val hasSettings = content.has("settings")
            import.restoreScopeGroup.isVisible =
                hasSettings && (hasProfiles || hasRules)
            import.restoreScopeGroup.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId == R.id.restore_scope_config_only) {
                    import.backupConfigurations.isChecked = hasProfiles
                    import.backupRules.isChecked = hasRules
                    import.backupSettings.isChecked = false
                } else {
                    import.backupConfigurations.isChecked = hasProfiles
                    import.backupRules.isChecked = hasRules
                    import.backupSettings.isChecked = hasSettings
                }
            }
            // 恢复策略（设计图实施，0.2.24 -9）：覆盖/合并，挪到导入对话框。
            // 默认取上次存的值；点“恢复”时把本次选择存回 DataStore，
            // finishImport 读它决定是清空重建还是合并。
            import.restoreStrategyGroup.check(
                if (DataStore.restoreStrategy == 1) R.id.restore_strategy_merge
                else R.id.restore_strategy_overwrite
            )
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    // A9: aborting here is safe — finishImport has not
                    // run yet, so nothing has been written.
                    if (!isAdded) return@setPositiveButton
                    DataStore.restoreStrategy =
                        if (import.restoreStrategyGroup.checkedRadioButtonId ==
                            R.id.restore_strategy_merge
                        ) 1 else 0
                    // S-B8: remember whether the VPN was running
                    // before stopping it for the import, so a failed
                    // import can try to bring it back up.
                    val vpnWasRunning = DataStore.serviceState.canStop
                    RiftApp.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnLifecycleDispatcher {
                        var webdavKept = false
                        runCatching {
                            webdavKept = finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            // A config change landed: schedule an auto backup.
                            AutoBackup.schedule()
                            if (!webdavKept) {
                                triggerFullRestart(if (isAdded) requireContext() else app)
                            }
                        }.onFailure {
                            Logs.w(it)
                            // S-B8: a failed import must not leave the
                            // VPN stopped. Best effort — if the stop
                            // broadcast is still propagating, the
                            // start may race it, but leaving it
                            // stopped for certain is worse.
                            if (vpnWasRunning) RiftApp.startService()
                            onMainDispatcher {
                                if (isAdded) {
                                    val message = if (importPartiallyApplied) {
                                        getString(R.string.backup_import_partial) +
                                            "\n" + it.readableMessage
                                    } else {
                                        it.readableMessage
                                    }
                                    alert(message).tryToShow()
                                }
                            }
                        }

                        onMainDispatcher {
                            try {
                                if (dialog.isShowing) dialog.dismiss()
                            } catch (_: Exception) {
                            }
                            // A8: a decrypt-failure restore must reach
                            // the user as its own notice, before the
                            // restart that would otherwise swallow it.
                            if (webdavKept && isAdded) {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setMessage(R.string.backup_import_webdav_kept)
                                    .setCancelable(false)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        triggerFullRestart(requireContext())
                                    }
                                    .create()
                                    .tryToShow()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ): Boolean {
        importPartiallyApplied = false
        // 先全量解析，任何一段解析失败都在写库前抛出，旧数据不动；
        // 以前是解析一段就 reset 一段，规则/设置段失败时节点已先被清空。
        var profiles: List<ProxyEntity>? = null
        var groups: List<ProxyGroup>? = null
        var rules: List<RuleEntity>? = null
        var settings: List<KeyValuePair>? = null
        if (profile && content.has("profiles")) {
            val list = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    list.add(ProxyEntity.CREATOR.createFromParcel(parcel))
                } finally {
                    parcel.recycle()
                }
            }
            profiles = list
            val glist = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    glist.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                } finally {
                    parcel.recycle()
                }
            }
            groups = glist
        }
        if (rule && content.has("rules")) {
            val list = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    list.add(ParcelizeBridge.createRule(parcel))
                } finally {
                    parcel.recycle()
                }
            }
            rules = list
        }
        if (setting && content.has("settings")) {
            val list = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                try {
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    list.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                } finally {
                    parcel.recycle()
                }
            }
            settings = list
        }
        // A10: validate uniqueness before any write. Dao.insert has
        // no conflict strategy, so a duplicate id/key inside one
        // section aborts that section's transaction — and without
        // this check, nodes/rules could already be committed while
        // settings rolled back. Existing rows cannot collide: each
        // section resets its table inside its own transaction first.
        fun <T> checkUnique(items: List<T>, keyOf: (T) -> Any?, label: String) {
            val seen = HashSet<Any?>()
            for (item in items) {
                val key = keyOf(item)
                if (!seen.add(key)) error("Duplicate $label in backup: $key")
            }
        }
        profiles?.let { checkUnique(it, { p -> p.id }, "profile id") }
        groups?.let { checkUnique(it, { g -> g.id }, "group id") }
        rules?.let { checkUnique(it, { r -> r.id }, "rule id") }
        settings?.let { checkUnique(it, { s -> s.key }, "setting key") }
        // Restore strategy (2026-11-10): overwrite (default) resets each
        // table before inserting; merge skips the reset and inserts with
        // conflict-ignore/replace instead.
        val merge = DataStore.restoreStrategy == 1
        // S-B7: validate-before-write, per library. A profile whose
        // groupId is not among the imported groups would be an
        // orphan no UI ever shows; fail the whole import before
        // any library is written, instead of committing nodes and
        // only then finding the groups do not cover them.
        if (profiles != null && groups != null) {
            // Merge mode: a profile may belong to a group that already
            // exists on this device, so current groups count too.
            val groupIds = (groups.map { it.id } +
                if (merge) RiftDatabase.groupDao.allGroups().map { it.id }
                else emptyList()).toSet()
            val orphaned = profiles.filter { it.groupId !in groupIds }
            if (orphaned.isNotEmpty()) {
                error(
                    "Backup profiles reference missing groups: " +
                        orphaned.take(3).joinToString { it.displayName() }
                )
            }
        }
        // S-B7 (cross-library honesty): RiftDatabase and
        // PublicDatabase are two Room databases and cannot share one
        // transaction. Each section above is fully parsed and
        // validated before its own transaction runs, and each
        // transaction is atomic on its own — but a later section can
        // still fail after an earlier one committed. That case is
        // reported, not hidden: finishImport sets
        // importPartiallyApplied, and the failure dialog tells the
        // user the import was only partially applied.

        if (profiles != null && groups != null) {
            RiftDatabase.instance.runInTransaction {
                if (merge) {
                    RiftDatabase.proxyDao.insertIgnore(profiles)
                    RiftDatabase.groupDao.insertIgnore(groups)
                } else {
                    RiftDatabase.proxyDao.reset()
                    RiftDatabase.proxyDao.insert(profiles)
                    RiftDatabase.groupDao.reset()
                    RiftDatabase.groupDao.insert(groups)
                }
            }
            importPartiallyApplied = true
        }
        if (rules != null) {
            RiftDatabase.instance.runInTransaction {
                if (merge) {
                    RiftDatabase.rulesDao.insertIgnore(rules)
                } else {
                    RiftDatabase.rulesDao.reset()
                    RiftDatabase.rulesDao.insert(rules)
                }
            }
            importPartiallyApplied = true
        }
        // A8: WebDAV restore, decided before the settings write and
        // carried out inside the settings transaction itself — not as
        // three writes after it, where a killed process could lose the
        // restore. The old behaviour for the normal case is unchanged:
        // a complete imported WebDAV configuration still wins.
        // Restore triggers: the current configuration exists, and
        // either the backup carries no WebDAV keys at all (an old
        // backup from before WebDAV was configured, or one from a
        // device without WebDAV — without this, reset() would
        // silently delete the working configuration), or the
        // imported server, username, or password is blank, or the
        // imported password ciphertext cannot be decrypted on this
        // device (e.g. a backup from another device's KeyStore).
        var webdavKept = false
        if (settings != null) {
            val curWebdavConfigured = DataStore.webdavUsername.isNotBlank() &&
                DataStore.webdavPasswordEncrypted.isNotBlank()
            val importedServer = settings.find { it.key == Key.WEBDAV_SERVER }
            val importedUsername = settings.find { it.key == Key.WEBDAV_USERNAME }
            val importedPassword = settings.find { it.key == Key.WEBDAV_PASSWORD }
            val hasWebdavKeys = importedServer != null ||
                importedUsername != null || importedPassword != null
            val importedPasswordValue = importedPassword?.string
            val importedDecryptFailed = importedPasswordValue != null &&
                importedPasswordValue.isNotBlank() &&
                WebDavBackup.decryptPassword(importedPasswordValue) == null
            val shouldRestore = curWebdavConfigured && (!hasWebdavKeys ||
                importedServer?.string.isNullOrBlank() ||
                importedUsername?.string.isNullOrBlank() ||
                importedPasswordValue.isNullOrBlank() ||
                importedDecryptFailed)
            webdavKept = shouldRestore && importedDecryptFailed
            if (merge) {
                // No reset: keep everything, imported settings win on
                // the same key. When the WebDAV config must be kept,
                // the imported WebDAV keys are dropped instead of
                // overwriting the working ones.
                val settingsToInsert = if (shouldRestore) {
                    settings.filterNot {
                        it.key == Key.WEBDAV_SERVER ||
                            it.key == Key.WEBDAV_USERNAME ||
                            it.key == Key.WEBDAV_PASSWORD
                    }
                } else {
                    settings
                }
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.insertOrReplace(settingsToInsert)
                }
            } else {
                val settingsToInsert = if (shouldRestore) {
                    // The settings transaction resets the whole table
                    // first, so "keep current" cannot mean "leave the keys
                    // out" — the current stored pairs must be re-inserted
                    // in the same transaction, or reset would delete them.
                    val currentPairs = listOfNotNull(
                        PublicDatabase.kvPairDao[Key.WEBDAV_SERVER],
                        PublicDatabase.kvPairDao[Key.WEBDAV_USERNAME],
                        PublicDatabase.kvPairDao[Key.WEBDAV_PASSWORD]
                    )
                    settings.filterNot {
                        it.key == Key.WEBDAV_SERVER ||
                            it.key == Key.WEBDAV_USERNAME ||
                            it.key == Key.WEBDAV_PASSWORD
                    } + currentPairs
                } else {
                    settings
                }
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset()
                    PublicDatabase.kvPairDao.insert(settingsToInsert)
                }
            }
        }
        return webdavKept
    }

}