package eu.kanade.tachiyomi.source

import android.content.Context
import com.elvishew.xlog.XLog
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.english.HentaiCafe
import eu.kanade.tachiyomi.source.online.english.Pururin
import eu.kanade.tachiyomi.source.online.english.Tsumino
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.source.BlacklistedSources
import exh.source.DelegatedHttpSource
import exh.source.EnhancedHttpSource
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import rx.Observable
import uy.kohesive.injekt.injectLazy

open class SourceManager(private val context: Context) {

    private val prefs: PreferencesHelper by injectLazy()

    private val sourcesMap = mutableMapOf<Long, Source>()

    private val stubSourcesMap = mutableMapOf<Long, StubSource>()

    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    init {
        createInternalSources().forEach { registerSource(it) }

        // Recreate sources when they change
        prefs.enableExhentai().asImmediateFlow {
            createEHSources().forEach { registerSource(it) }
        }.launchIn(scope)

        registerSource(MergedSource())
    }

    open fun get(sourceKey: Long): Source? {
        return sourcesMap[sourceKey]
    }

    fun getOrStub(sourceKey: Long): Source {
        return sourcesMap[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            StubSource(sourceKey)
        }
    }

    fun getOnlineSources() = sourcesMap.values.filterIsInstance<HttpSource>()

    fun getVisibleOnlineSources() = sourcesMap.values.filterIsInstance<HttpSource>().filter {
        it.id !in BlacklistedSources.HIDDEN_SOURCES
    }

    fun getCatalogueSources() = sourcesMap.values.filterIsInstance<CatalogueSource>()

    fun getVisibleCatalogueSources() = sourcesMap.values.filterIsInstance<CatalogueSource>().filter {
        it.id !in BlacklistedSources.HIDDEN_SOURCES
    }

    fun getDelegatedCatalogueSources() = sourcesMap.values.filterIsInstance<EnhancedHttpSource>().mapNotNull { enhancedHttpSource ->
        enhancedHttpSource.enchancedSource as? DelegatedHttpSource
    }

    internal fun registerSource(source: Source, overwrite: Boolean = false) {
        // EXH -->
        val sourceQName = source::class.qualifiedName
        val delegate = DELEGATED_SOURCES[sourceQName]
        val newSource = if (source is HttpSource && delegate != null) {
            XLog.d("[EXH] Delegating source: %s -> %s!", sourceQName, delegate.newSourceClass.qualifiedName)
            EnhancedHttpSource(
                source,
                delegate.newSourceClass.constructors.find { it.parameters.size == 1 }!!.call(source)
            )
        } else source

        if (source.id in BlacklistedSources.BLACKLISTED_EXT_SOURCES) {
            XLog.d("[EXH] Removing blacklisted source: (id: %s, name: %s, lang: %s)!", source.id, source.name, (source as? CatalogueSource)?.lang)
            return
        }
        // EXH <--

        if (overwrite || !sourcesMap.containsKey(source.id)) {
            sourcesMap[source.id] = newSource
        }
    }

    internal fun unregisterSource(source: Source) {
        sourcesMap.remove(source.id)
    }

    private fun createInternalSources(): List<Source> = listOf(
        LocalSource(context)
    )

    private fun createEHSources(): List<Source> {
        val exSrcs = mutableListOf<HttpSource>(
            EHentai(EH_SOURCE_ID, false, context)
        )
        if (prefs.enableExhentai().get()) {
            exSrcs += EHentai(EXH_SOURCE_ID, true, context)
        }
        exSrcs += NHentai(context)
        return exSrcs
    }

    private inner class StubSource(override val id: Long) : Source {

        override val name: String
            get() = id.toString()

        override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
            return Observable.error(getSourceNotInstalledException())
        }

        override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
            return Observable.error(getSourceNotInstalledException())
        }

        override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
            return Observable.error(getSourceNotInstalledException())
        }

        override fun toString(): String {
            return name
        }

        private fun getSourceNotInstalledException(): Exception {
            return Exception(context.getString(R.string.source_not_installed, id.toString()))
        }
    }

    companion object {
        val DELEGATED_SOURCES = listOf(
            DelegatedSource(
                "Hentai Cafe",
                260868874183818481,
                "eu.kanade.tachiyomi.extension.all.foolslide.HentaiCafe",
                HentaiCafe::class
            ),
            DelegatedSource(
                "Pururin",
                2221515250486218861,
                "eu.kanade.tachiyomi.extension.en.pururin.Pururin",
                Pururin::class
            ),
            DelegatedSource(
                "Tsumino",
                6707338697138388238,
                "eu.kanade.tachiyomi.extension.en.tsumino.Tsumino",
                Tsumino::class
            )
        ).associateBy { it.originalSourceQualifiedClassName }

        data class DelegatedSource(
            val sourceName: String,
            val sourceId: Long,
            val originalSourceQualifiedClassName: String,
            val newSourceClass: KClass<out DelegatedHttpSource>
        )
    }
}

class SourceNotFoundException(message: String, val id: Long) : Exception(message)
