package light.rift.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import light.rift.R
import light.rift.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)

        // 分类总表（2026-11-11 -9）：这里不再直接嵌整页偏好，改嵌
        // SettingsHubFragment（7 个分类各一张小卡），点一行进子页。
        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, SettingsHubFragment())
            .commitAllowingStateLoss()
    }

}