package eu.kanade.tachiyomi.ui.recent.history

import android.view.View
import com.bumptech.glide.load.engine.DiskCacheStrategy
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.MangaChapterHistory
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.glide.toMangaThumbnail
import eu.kanade.tachiyomi.databinding.HistoryItemBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.lang.toTimestampString
import java.util.Date

/**
 * Holder that contains recent manga item
 * Uses R.layout.item_recently_read.
 * UI related actions should be called from here.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new recent chapter holder.
 */
class HistoryHolder(
    view: View,
    val adapter: HistoryAdapter
) : BaseFlexibleViewHolder(view, adapter) {
    private val binding = HistoryItemBinding.bind(view)

    init {
        binding.holder.setOnClickListener {
            adapter.itemClickListener.onItemClick(bindingAdapterPosition)
        }

        binding.remove.setOnClickListener {
            adapter.removeClickListener.onRemoveClick(bindingAdapterPosition)
        }

        binding.resume.setOnClickListener {
            adapter.resumeClickListener.onResumeClick(bindingAdapterPosition)
        }
    }

    /**
     * Set values of view
     *
     * @param item item containing history information
     */
    fun bind(item: MangaChapterHistory) {
        // Retrieve objects
        val (manga, chapter, history) = item

        // Set manga title
        binding.mangaTitle.text = manga.title

        // Set source + chapter title
        val formattedNumber = adapter.decimalFormat.format(chapter.chapter_number.toDouble())
        binding.mangaSource.text =
            itemView.context.getString(R.string.recent_manga_source)
                .format(adapter.sourceManager.getOrStub(manga.source).toString(), formattedNumber)

        // Set last read timestamp title
        binding.lastRead.text = Date(history.last_read).toTimestampString()

        // Set cover
        GlideApp.with(itemView.context).clear(binding.cover)
        GlideApp.with(itemView.context)
            .load(manga.toMangaThumbnail())
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .centerCrop()
            .into(binding.cover)
    }
}
