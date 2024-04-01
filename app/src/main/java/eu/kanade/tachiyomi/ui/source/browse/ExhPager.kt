package eu.kanade.tachiyomi.ui.source.browse

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.util.lang.awaitSingle
import exh.EH_SOURCE_ID

open class ExhPager(val source: CatalogueSource, val query: String, val filters: FilterList) : Pager() {
    override suspend fun requestNextPage() {
        val page = currentPage

        val observable =
            if (query.isBlank() && filters.isEmpty()) {
                source.fetchPopularManga(page)
            } else {
                source.fetchSearchManga(page, query, filters)
            }

        val mangasPage = observable.awaitSingle()

        if (mangasPage.mangas.isNotEmpty()) {
            onPageReceived(mangasPage)
        } else {
            throw NoResultsException()
        }
    }

    override fun onPageReceived(mangasPage: MangasPage) {
        val page = currentPage
        if (query.isBlank() && filters.isEmpty() && source.id == EH_SOURCE_ID) {
            currentPage++
        } else {
            currentPage = urlToId(mangasPage.mangas.lastOrNull()?.url)
        }
        hasNextPage = mangasPage.hasNextPage && mangasPage.mangas.isNotEmpty()
        results.call(Pair(page, mangasPage.mangas))
    }

    private fun urlToId(url: String?): Int {
        val urlstring = url ?: return 0
        val match = Regex("\\/g\\/([0-9]*)\\/").find(urlstring)!!.destructured.toList().first()
        return match.toInt()
    }
}
