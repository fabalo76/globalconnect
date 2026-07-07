package one.globalconnect.xtmsagent

import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import one.globalconnect.xtmsagent.btn_move.GridAdapter
import one.globalconnect.xtmsagent.btn_move.ItemTouchHelperCallback

private const val GRID_COLUMNS = 2

class LauncherPagerAdapter(
    private val pages: List<List<GridAdapter.ButtonItem>>,
    private val itemHeightPx: Int,
    private val pageStartIndices: List<Int>
) : RecyclerView.Adapter<LauncherPagerAdapter.PageHolder>() {

    inner class PageHolder(val recycler: RecyclerView) : RecyclerView.ViewHolder(recycler)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val rv = RecyclerView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = GridLayoutManager(parent.context, GRID_COLUMNS)
            isNestedScrollingEnabled = false
        }
        return PageHolder(rv)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val pageAdapter = GridAdapter(pages[position], itemHeightPx, pageStartIndices[position])
        holder.recycler.adapter = pageAdapter
        ItemTouchHelper(ItemTouchHelperCallback(pageAdapter)).attachToRecyclerView(holder.recycler)
    }

    override fun getItemCount() = pages.size
}
