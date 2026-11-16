package light.rift.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import light.rift.R
import light.rift.widget.ListListener

// 设置分类子页（2026-11-11 -7）：外壳与 SettingsFragment 一样，
// 里面嵌带分类参数的 SettingsPreferenceFragment，只显示该类的偏好。
// 标题是分类名；返回键回分类总表（MainActivity 里处理）。
class SettingsCategoryFragment : ToolbarFragment(R.layout.layout_config_settings) {

    companion object {
        private const val ARG_CATEGORY = "category"

        fun newInstance(category: SettingsPreferenceFragment.Category) =
            SettingsCategoryFragment().apply {
                arguments = Bundle().apply { putString(ARG_CATEGORY, category.name) }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        val category = arguments?.getString(ARG_CATEGORY)?.let {
            runCatching { SettingsPreferenceFragment.Category.valueOf(it) }.getOrDefault(
                SettingsPreferenceFragment.Category.GENERAL
            )
        } ?: SettingsPreferenceFragment.Category.GENERAL
        toolbar.setTitle(category.titleRes)

        // 子页顶栏用返回箭头（不用汉堡菜单）：点一下回设置总表，
        // 跟系统返回键行为一致（MainActivity 里分类子页回总表）。
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity?)?.displayFragment(SettingsFragment())
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, SettingsPreferenceFragment.newInstance(category))
            .commitAllowingStateLoss()
    }

}
