package eu.kanade.tachiyomi.ui.source.latest

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.source.browse.Pager
import eu.kanade.tachiyomi.util.lang.awaitSingle

/**
 * LatestUpdatesPager inherited from the general Pager.
 */
class LatestUpdatesPager(val source: CatalogueSource) : Pager() {

    override suspend fun requestNextPage() {
        val mangasPage = source.fetchLatestUpdates(currentPage).awaitSingle()
        onPageReceived(mangasPage)
    }
}
