package eu.kanade.tachiyomi.ui.migration.manga.design

import android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
import android.view.View
import eu.kanade.tachiyomi.source.icon
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.view.roundTextIcon
import kotlinx.android.synthetic.main.migration_source_item.image
import kotlinx.android.synthetic.main.migration_source_item.reorder
import kotlinx.android.synthetic.main.migration_source_item.title

class MigrationSourceHolder(view: View, val adapter: MigrationSourceAdapter) :
        BaseFlexibleViewHolder(view, adapter) {
    init {
        setDragHandleView(reorder)
    }

    fun bind(source: HttpSource, sourceEnabled: Boolean) {
        // Set capitalized title.
        title.text = source.name.capitalize()

        // Update circle letter image.
        itemView.post {
            val icon = source.icon()
            if (icon != null) image.setImageDrawable(icon)
            else image.roundTextIcon(source.name)
        }

        if (sourceEnabled) {
            title.alpha = 1.0f
            image.alpha = 1.0f
            title.paintFlags = title.paintFlags and STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            title.alpha = DISABLED_ALPHA
            image.alpha = DISABLED_ALPHA
            title.paintFlags = title.paintFlags or STRIKE_THRU_TEXT_FLAG
        }
    }

    /**
     * Called when an item is released.
     *
     * @param position The position of the released item.
     */
    override fun onItemReleased(position: Int) {
        super.onItemReleased(position)
        adapter.updateItems()
    }

    companion object {
        private const val DISABLED_ALPHA = 0.3f
    }
}
