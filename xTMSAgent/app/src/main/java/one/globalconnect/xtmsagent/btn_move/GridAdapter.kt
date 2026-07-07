package one.globalconnect.xtmsagent.btn_move

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import androidx.core.widget.TextViewCompat
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import one.globalconnect.xtmsagent.MainActivity
import one.globalconnect.xtmsagent.R
import java.util.Collections

private const val TAG = "GridAdapter"

class GridAdapter(
    items1: List<ButtonItem>,
    private val itemHeightPx: Int = 0,
    private val pageStartIndex: Int = 0
) : ItemTouchHelperCallback.ItemTouchHelperAdapter, RecyclerView.Adapter<GridAdapter.ViewHolder>() {
    private val items: List<ButtonItem> = items1
    data class ButtonItem(
        val text: String,
        val packageName: String,
        val backgroundColor: Int,
        var iconDrawable: Drawable?,
        var onClickAction: Runnable,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_button, parent, false)
        if (itemHeightPx > 0) {
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx
            )
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int {
        return items.size
    }

    //ItemTouchHelperAdapter
    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        Collections.swap(items, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onClearView(recyclerView: RecyclerView) {
        for (i in items.indices) {
            val globalIndex = pageStartIndex + i
            if (globalIndex >= MainActivity.appList.size) break
            val item = items[i]
            MainActivity.appList[globalIndex] = MainActivity.Companion.AppInfo(
                item.text, item.packageName, item.backgroundColor
            )
        }
        MainActivity.SaveAppList()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var button: Button
        var iconView: ImageView
        var btnItem: ButtonItem? = null

        init{
            button = itemView.findViewById(R.id.button)
            iconView = itemView.findViewById(R.id.button_icon)
        }

        @Suppress("UNUSED_PARAMETER")
        fun bind(item: ButtonItem, _position: Int) {
            btnItem = item
            button.text = item.text
            button.setTextColor(Color.parseColor(MainActivity.stTheme.font_color))
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                button, 8, MainActivity.stTheme.font_size, 1, TypedValue.COMPLEX_UNIT_SP
            )
            //if(10 < button.text.length)
            //    button.setPadding(0,0,0,10)
            Log.d(TAG, "btn[${item.text}] color=#%08X".format(item.backgroundColor))
            button.background = buildGradientBackground(item.backgroundColor, button)
            // Clear the theme's backgroundTintList so it doesn't tint our gradient.
            button.backgroundTintList = null
            button.setOnClickListener {
                item.onClickAction.run()
            }

            // 設定 icon 到 ImageView
            if (null != item.iconDrawable) {
                iconView.visibility = View.VISIBLE
                iconView.setImageDrawable(item.iconDrawable)
            } else {
                iconView.visibility = View.GONE // 如果沒有圖示，就隱藏 ImageView
            }
        }

        companion object {
            /**
             * Builds a rounded-rectangle gradient drawable that mimics the original
             * launcher's 3D button look: lighter shade at the top fading to the base
             * color at the bottom, with a drop shadow supplied by android:elevation.
             */
            private fun buildGradientBackground(baseColor: Int, button: Button): GradientDrawable {
                val cornerPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 12f,
                    button.context.resources.displayMetrics
                )
                return GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(lighten(baseColor, 0.40f), baseColor)
                ).apply {
                    cornerRadius = cornerPx
                }
            }

            /** Blends [color] toward white by [factor] (0 = unchanged, 1 = white). */
            private fun lighten(color: Int, factor: Float): Int {
                val r = (Color.red(color)   + (255 - Color.red(color))   * factor).toInt().coerceIn(0, 255)
                val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
                val b = (Color.blue(color)  + (255 - Color.blue(color))  * factor).toInt().coerceIn(0, 255)
                return Color.argb(Color.alpha(color), r, g, b)
            }
        }
    }
}