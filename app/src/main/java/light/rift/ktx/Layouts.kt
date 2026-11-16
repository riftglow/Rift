package light.rift.ktx

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    override fun scrollVerticallyBy(
        dx: Int, recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        // 中间环绕版：开关固定在底部中间，不再由列表滚动藏显。
        // 原版在这里按滚动藏开关的逻辑已废弃，栏的藏显只由 StatsBar 的
        // hideOnScroll 负责，开关始终可点，不会再因滚动而消失。
        return super.scrollVerticallyBy(dx, recycler, state)
    }

}
