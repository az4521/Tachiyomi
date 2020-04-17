package eu.kanade.tachiyomi.ui.migration

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.databinding.MigrationControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.popControllerWithTag
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationListController
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationProcedureConfig
import eu.kanade.tachiyomi.util.await
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.view.RecyclerWindowInsetsListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrationController : NucleusController<MigrationPresenter>(),
        FlexibleAdapter.OnItemClickListener,
        SourceAdapter.OnSelectClickListener,
        SourceAdapter.OnAutoClickListener,
        MigrationInterface {

    private var adapter: FlexibleAdapter<IFlexible<*>>? = null

    private var title: String? = null
        set(value) {
            field = value
            setTitle()
        }

    private lateinit var binding: MigrationControllerBinding

    override fun createPresenter(): MigrationPresenter {
        return MigrationPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = MigrationControllerBinding.inflate(inflater)
        return binding.root
    }

    fun searchController(manga: Manga): SearchController {
        val controller = SearchController(manga)
        controller.targetController = this

        return controller
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = FlexibleAdapter(null, this)
        binding.migrationRecycler.layoutManager = LinearLayoutManager(view.context)
        binding.migrationRecycler.adapter = adapter
        binding.migrationRecycler.setOnApplyWindowInsetsListener(RecyclerWindowInsetsListener)
    }

    override fun onDestroyView(view: View) {
        adapter = null
        super.onDestroyView(view)
    }

    override fun getTitle(): String? {
        return title
    }

    override fun handleBack(): Boolean {
        return if (presenter.state.selectedSource != null) {
            presenter.deselectSource()
            true
        } else {
            super.handleBack()
        }
    }

    fun render(state: ViewState) {
        if (state.selectedSource == null) {
            title = resources?.getString(R.string.label_migration)
            if (adapter !is SourceAdapter) {
                adapter = SourceAdapter(this)
                binding.migrationRecycler.adapter = adapter
            }
            adapter?.updateDataSet(state.sourcesWithManga)
        } else {
            title = state.selectedSource.toString()
            if (adapter !is MangaAdapter) {
                adapter = MangaAdapter(this)
                binding.migrationRecycler.adapter = adapter
            }
            adapter?.updateDataSet(state.mangaForSource)
        }
    }

    fun renderIsReplacingManga(state: ViewState) {
        if (state.isReplacingManga) {
            if (router.getControllerWithTag(LOADING_DIALOG_TAG) == null) {
                LoadingController().showDialog(router, LOADING_DIALOG_TAG)
            }
        } else {
            router.popControllerWithTag(LOADING_DIALOG_TAG)
        }
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        val item = adapter?.getItem(position) ?: return false

        if (item is MangaItem) {
            val controller = SearchController(item.manga)
            controller.targetController = this

            router.pushController(controller.withFadeTransaction())
        } else if (item is SourceItem) {
            presenter.setSelectedSource(item.source)
        }
        return false
    }

    override fun onSelectClick(position: Int) {
        onItemClick(null, position)
    }

    override fun onAutoClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return

        launchUI {
            val manga = Injekt.get<DatabaseHelper>().getFavoriteMangas().asRxSingle().await(
                Schedulers.io())
            val sourceMangas = manga.asSequence().filter { it.source == item.source.id }.map { it.id!! }.toList()
            withContext(Dispatchers.Main) {
                router.pushController(
                    if (Injekt.get<PreferencesHelper>().skipPreMigration().getOrDefault()) {
                        MigrationListController.create(
                            MigrationProcedureConfig(sourceMangas, null)
                        )
                    } else { PreMigrationController.create(sourceMangas) }
                    .withFadeTransaction())
            }
        }
    }

    override fun migrateManga(prevManga: Manga, manga: Manga, replace: Boolean): Manga? {
        presenter.migrateManga(prevManga, manga, replace)
        return null
    }

    class LoadingController : DialogController() {

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog.Builder(activity!!)
                    .progress(true, 0)
                    .content(R.string.migrating)
                    .cancelable(false)
                    .build()
        }
    }

    companion object {
        const val LOADING_DIALOG_TAG = "LoadingDialog"
    }
}

interface MigrationInterface {
    fun migrateManga(prevManga: Manga, manga: Manga, replace: Boolean): Manga?
}
