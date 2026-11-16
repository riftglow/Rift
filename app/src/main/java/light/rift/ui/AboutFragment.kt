package light.rift.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.util.Linkify
import android.view.View
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import light.rift.R
import light.rift.databinding.LayoutAboutBinding
import light.rift.ktx.*
import light.rift.widget.ListListener
import libcore.Libcore
import androidx.core.net.toUri
import light.rift.RiftApp
import light.rift.database.DataStore

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        parentFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        // 关于页只显示简短声明，不再全文显示 LICENSE（GPL 全文太长）；
        // 完整 LICENSE 仍在 assets 里，随应用分发，合规不受影响。
        binding.license.setText(R.string.license_short)
        Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
    }

    class AboutContent : MaterialAboutFragment() {

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(RiftApp.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/riftglow/Rift/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    // 只查正式版（0.2.29 -26）：预览版不进更新检查，走聊天发包。
                                    // 应用内下载安装，见 AppUpdater。
                                    AppUpdater.checkManually(requireActivity())
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { }
                                .build())
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(false)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_sanitizer_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/riftglow/Rift"

                                    )
                                }
                                .build())

                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
        }

    }

}
