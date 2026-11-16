package light.rift.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import light.rift.R
import light.rift.databinding.LayoutToolsBinding

/**
 * The tools screen: a two-tab pager holding the network diagnostics page
 * and the backup page. Long-pressing a tab does nothing (it only exists to
 * swallow the toast the tab view would otherwise show).
 */
class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)

        val pages = listOf<NamedFragment>(NetworkFragment(), BackupFragment())
        val binding = LayoutToolsBinding.bind(view)
        binding.toolsPager.adapter = ToolsAdapter(pages)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = pages[position].name()
            tab.view.setOnLongClickListener { true }
        }.attach()
    }

    /** Pager adapter over the fixed [pages] list. */
    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount(): Int = tools.size

        override fun createFragment(position: Int): Fragment = tools[position]

    }

}
