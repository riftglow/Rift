package light.rift.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.language.json.JsonLanguage
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import light.rift.ktx.RiftAlertDialogBuilder
import light.rift.Key
import light.rift.R
import light.rift.database.DataStore
import light.rift.databinding.LayoutEditConfigBinding
import light.rift.ktx.getColorAttr
import light.rift.ktx.readableMessage
import light.rift.ktx.toStringPretty
import light.rift.ui.ThemedActivity
import light.rift.widget.ListListener
import light.rift.core.ui.ExtendedKeyboard
import org.json.JSONObject

class ConfigEditActivity : ThemedActivity() {

    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    lateinit var binding: LayoutEditConfigBinding

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        binding = LayoutEditConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        setupEditor()
        setupActionButtons()
        setupExtendedKeyboard()

        val keyboardContainer = findViewById<LinearLayout>(R.id.keyboard_container)
        installKeyboardInsetsHandler(keyboardContainer)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    private fun setupEditor() {
        binding.editor.apply {
            language = JsonLanguage()
            setHorizontallyScrolling(true)
            if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(DataStore.profileCacheStore.getString(key) ?: "")
            }
            addTextChangedListener {
                if (!dirty) {
                    dirty = true
                    DataStore.dirty = true
                }
            }
        }
    }

    private fun setupActionButtons() {
        binding.actionTab.setOnClickListener {
            runQuietly { binding.editor.insert(binding.editor.tab()) }
        }
        binding.actionUndo.setOnClickListener {
            runQuietly { binding.editor.undo() }
        }
        binding.actionRedo.setOnClickListener {
            runQuietly { binding.editor.redo() }
        }
        binding.actionFormat.setOnClickListener {
            formatText()?.let {
                binding.editor.setTextContent(it)
            }
        }
    }

    private fun setupExtendedKeyboard() {
        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { char ->
            runQuietly { binding.editor.insert(char) }
        }
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList("{},:_\"".map { it.toString() })
        extendedKeyboard.setBackgroundColor(getColorAttr(R.attr.primaryOrTextPrimary))
    }

    /**
     * Lifts the keyboard container above the IME: the bottom margin follows the
     * visible IME height minus the system bar height, and drops back to zero
     * once the keyboard is hidden.
     */
    private fun installKeyboardInsetsHandler(container: LinearLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                // systemBar insets are applied to the bottom of the keyboard
                if (imeVisible) {
                    bottomMargin = imeInsets.bottom - systemBarInsets.bottom
                } else {
                    bottomMargin = 0
                }
            }

            WindowInsetsCompat.CONSUMED
        }
    }

    /** Editor actions must never crash the page: a failed edit is just skipped. */
    private inline fun runQuietly(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    fun formatText(): String? {
        try {
            val txt = binding.editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            RiftAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    fun saveAndExit() {
        formatText()?.let {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, it)
            } else {
                DataStore.profileCacheStore.putString(key, it)
            }
            finish()
        }
    }

    override fun onBackPressed() {
        if (dirty) UnsavedChangesDialogFragment().apply { key() }
            .show(supportFragmentManager, null) else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        // Same unsaved-changes check as onBackPressed (M-8): the toolbar ✕
        // must not silently discard edits.
        if (dirty) {
            UnsavedChangesDialogFragment().apply { key() }
                .show(supportFragmentManager, null)
        } else if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
