package light.rift.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import light.rift.R
import light.rift.ktx.FixedLinearLayoutManager

// 设置分类总表（2026-11-11 -9）：7 个分类各占一张独立小卡片（像单间），
// 不再是 -8 的七行共用一张大卡。点一行进该分类子页，返回键回总表。
// hub 只是导航入口，不用 Preference 框架，改用普通 Fragment + RecyclerView。
// 页底用 colorPrimary（白天 #F2F4F3、夜间 #121212），跟备份页一致；
// 背景只设在 hub 自己的根视图上，不碰共用的 layout_config_settings，
// 所以分类子页的偏好列表底色保持原样。
class SettingsHubFragment : Fragment() {

    // 分类与标题一一对应（标题取自 SettingsPreferenceFragment.Category.titleRes，
    // 跟 settings_*.xml 里的分类标题是同一批字符串）。
    private val categories = listOf(
        SettingsPreferenceFragment.Category.GENERAL,
        SettingsPreferenceFragment.Category.ROUTE,
        SettingsPreferenceFragment.Category.DNS,
        SettingsPreferenceFragment.Category.HYSTERIA2,
        SettingsPreferenceFragment.Category.ANYTLS,
        SettingsPreferenceFragment.Category.INBOUND,
        SettingsPreferenceFragment.Category.MISC,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? = inflater.inflate(R.layout.layout_settings_hub, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.hub_list)
        list.layoutManager = FixedLinearLayoutManager(list)
        list.adapter = HubAdapter()
    }

    private inner class HubAdapter : RecyclerView.Adapter<HubAdapter.HubHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HubHolder {
            val item = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings_hub, parent, false)
            return HubHolder(item)
        }

        override fun onBindViewHolder(holder: HubHolder, position: Int) {
            val category = categories[position]
            holder.title.setText(category.titleRes)
            holder.itemView.setOnClickListener {
                (activity as MainActivity?)?.displayFragment(
                    SettingsCategoryFragment.newInstance(category)
                )
            }
        }

        override fun getItemCount(): Int = categories.size

        inner class HubHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.hub_title)
        }
    }
}
