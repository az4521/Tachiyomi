package eu.kanade.tachiyomi.ui.library

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.tfcporciuncula.flow.Preference
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferenceValues.DisplayMode
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.databinding.SourceComfortableGridItemBinding
import eu.kanade.tachiyomi.databinding.SourceCompactGridItemBinding
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.widget.AutofitRecyclerView
import exh.isNamespaceSource
import exh.metadata.metadata.base.RaisedTag
import exh.util.SourceTagsUtil.Companion.TAG_TYPE_EXCLUDE
import exh.util.SourceTagsUtil.Companion.getRaisedTags
import exh.util.SourceTagsUtil.Companion.parseTag
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryItem(val manga: LibraryManga, private val libraryDisplayMode: Preference<DisplayMode>) :
    AbstractFlexibleItem<LibraryHolder<*>>(), IFilterable<Pair<String, Boolean>> {

    private val sourceManager: SourceManager = Injekt.get()
    private val trackManager: TrackManager = Injekt.get()
    private val db: DatabaseHelper = Injekt.get()
    private val source by lazy {
        sourceManager.get(manga.source)
    }
    // SY <--

    var downloadCount = -1
    var unreadCount = -1

    // SY -->
    var startReadingButton = false
    // SY <--

    override fun getLayoutRes(): Int {
        return when (libraryDisplayMode.get()) {
            DisplayMode.COMPACT_GRID -> R.layout.source_compact_grid_item
            DisplayMode.COMFORTABLE_GRID -> R.layout.source_comfortable_grid_item
            DisplayMode.LIST -> R.layout.source_list_item
        }
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): LibraryHolder<*> {
        return when (libraryDisplayMode.get()) {
            DisplayMode.COMPACT_GRID -> {
                val parent = adapter.recyclerView as AutofitRecyclerView
                val coverHeight = parent.itemWidth / 3 * 4
                val binding = SourceCompactGridItemBinding.bind(view)
                view.apply {
                    binding.card.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, coverHeight)
                    binding.gradient.layoutParams = FrameLayout.LayoutParams(
                        MATCH_PARENT, coverHeight / 2, Gravity.BOTTOM
                    )
                }
                LibraryGridHolder(view, adapter)
            }
            DisplayMode.COMFORTABLE_GRID -> {
                val parent = adapter.recyclerView as AutofitRecyclerView
                val coverHeight = parent.itemWidth / 3 * 4
                val binding = SourceComfortableGridItemBinding.bind(view)
                view.apply {
                    binding.card.layoutParams = ConstraintLayout.LayoutParams(
                        MATCH_PARENT, coverHeight
                    )
                }
                LibraryComfortableGridHolder(view, adapter)
            }
            DisplayMode.LIST -> {
                LibraryListHolder(view, adapter)
            }
        }
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: LibraryHolder<*>,
        position: Int,
        payloads: List<Any?>?
    ) {
        holder.onSetValues(this)
    }

    /**
     * Returns true if this item is draggable.
     */
    override fun isDraggable(): Boolean {
        return true
    }

    /**
     * Filters a manga depending on a query.
     *
     * @param constraint the query to apply.
     * @return true if the manga should be included, false otherwise.
     */
    override fun filter(constraint: Pair<String, Boolean>): Boolean {
        return manga.title.contains(constraint.first, true) ||
            (manga.author?.contains(constraint.first, true) ?: false) ||
            (manga.artist?.contains(constraint.first, true) ?: false) ||
            (source?.name?.contains(constraint.first, true) ?: false) ||
            (Injekt.get<TrackManager>().hasLoggedServices() && filterTracks(constraint.first, db.getTracks(manga).executeAsBlocking())) ||
            constraint.second && ehContainsGenre(constraint.first)
    }

    private fun filterTracks(constraint: String, tracks: List<Track>): Boolean {
        return tracks.any {
            val trackService = trackManager.getService(it.sync_id)
            if (trackService != null) {
                val status = trackService.getStatus(it.status)
                val name = trackService.name
                return@any status.contains(constraint, true) || name.contains(constraint, true)
            }
            return@any false
        }
    }

    private fun ehContainsGenre(constraint: String): Boolean {
        val genres = manga.getGenres()
        val raisedTags = if (source?.isNamespaceSource() == true) {
            manga.getRaisedTags(genres)
        } else null
        return if (constraint.contains(" ") || constraint.contains("\"")) {
            var cleanConstraint = ""
            var ignoreSpace = false
            for (i in constraint.trim().lowercase()) {
                when (i) {
                    ' ' -> {
                        cleanConstraint = if (!ignoreSpace) {
                            "$cleanConstraint,"
                        } else {
                            "$cleanConstraint "
                        }
                    }
                    '"' -> {
                        ignoreSpace = !ignoreSpace
                    }
                    else -> {
                        cleanConstraint += i.toString()
                    }
                }
            }
            cleanConstraint.split(",").all {
                if (raisedTags == null) containsGenre(it.trim(), genres) else containsRaisedGenre(
                    parseTag(it.trim()), raisedTags
                )
            }
        } else if (raisedTags == null) {
            containsGenre(constraint, genres)
        } else {
            containsRaisedGenre(parseTag(constraint), raisedTags)
        }
    }

    private fun containsRaisedGenre(tag: RaisedTag, genres: List<RaisedTag>): Boolean {
        val genre = genres.find {
            (it.namespace?.lowercase() == tag.namespace?.lowercase() && it.name.lowercase() == tag.name.lowercase())
        }
        return if (tag.type == TAG_TYPE_EXCLUDE) {
            genre == null
        } else {
            genre != null
        }
    }
    // SY <--

    private fun containsGenre(tag: String, genres: List<String>?): Boolean {
        return if (tag.startsWith("-")) {
            genres?.find {
                it.trim().lowercase() == tag.substringAfter("-").lowercase()
            } == null
        } else {
            genres?.find {
                it.trim().lowercase() == tag.lowercase()
            } != null
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is LibraryItem) {
            return manga.id == other.manga.id
        }
        return false
    }

    override fun hashCode(): Int {
        return manga.id!!.hashCode()
    }
}
