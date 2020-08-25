package eu.kanade.tachiyomi.ui.library

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.tabs.TabLayout
import com.jakewharton.rxrelay.BehaviorRelay
import com.jakewharton.rxrelay.PublishRelay
import com.tfcporciuncula.flow.Preference
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.databinding.LibraryControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.RootController
import eu.kanade.tachiyomi.ui.base.controller.SecondaryDrawerController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.migration.MigrationController
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.util.hasCustomCover
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.inflate
import eu.kanade.tachiyomi.util.view.visible
import exh.favorites.FavoritesIntroDialog
import exh.favorites.FavoritesSyncStatus
import exh.ui.LoaderManager
import java.util.concurrent.TimeUnit
import kotlinx.android.synthetic.main.main_activity.drawer
import kotlinx.android.synthetic.main.main_activity.tabs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.appcompat.queryTextChanges
import reactivecircus.flowbinding.viewpager.pageSelections
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LibraryController(
    bundle: Bundle? = null,
    private val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get()
) : NucleusController<LibraryControllerBinding, LibraryPresenter>(bundle),
    RootController,
    TabbedController,
    SecondaryDrawerController,
    ActionMode.Callback,
    ChangeMangaCoverDialog.Listener,
    ChangeMangaCategoriesDialog.Listener,
    DeleteLibraryMangasDialog.Listener,
    LibraryCategoryAdapter.LibraryListener {

    /**
     * Position of the active category.
     */
    private var activeCategory: Int = preferences.lastUsedCategory().get()

    /**
     * Action mode for selections.
     */
    private var actionMode: ActionMode? = null

    /**
     * Library search query.
     */
    private var query = ""

    /**
     * Currently selected mangas.
     */
    val selectedMangas = mutableSetOf<Manga>()

    private var selectedCoverManga: Manga? = null

    /**
     * Relay to notify the UI of selection updates.
     */
    val selectionRelay: PublishRelay<LibrarySelectionEvent> = PublishRelay.create()

    /**
     * Current mangas to move.
     */
    private var migratingMangas = mutableSetOf<Manga>()

    /**
     * Relay to notify search query changes.
     */
    val searchRelay: BehaviorRelay<String> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager for updates.
     */
    val libraryMangaRelay: BehaviorRelay<LibraryMangaEvent> = BehaviorRelay.create()

    /**
     * Relay to notify the library's viewpager to select all manga
     */
    val selectAllRelay: PublishRelay<Int> = PublishRelay.create()

    /**
     * Relay to notify the library's viewpager to reotagnize all
     */
    val reorganizeRelay: PublishRelay<Pair<Int, Int>> = PublishRelay.create()

    /**
     * Relay to notify the library's viewpager to select the inverse
     */
    val selectInverseRelay: PublishRelay<Int> = PublishRelay.create()

    /**
     * Number of manga per row in grid mode.
     */
    var mangaPerRow = 0
        private set

    /**
     * Adapter of the view pager.
     */
    private var adapter: LibraryAdapter? = null

    /**
     * Navigation view containing filter/sort/display items.
     */
    private var navView: LibraryNavigationView? = null

    /**
     * Drawer listener to allow swipe only for closing the drawer.
     */
    private var drawerListener: androidx.drawerlayout.widget.DrawerLayout.DrawerListener? = null

    private var tabsVisibilityRelay: BehaviorRelay<Boolean> = BehaviorRelay.create(false)

    private var tabsVisibilitySubscription: Subscription? = null

    // --> EH
    // Sync dialog
    private var favSyncDialog: MaterialDialog? = null
    // Old sync status
    private var oldSyncStatus: FavoritesSyncStatus? = null
    // Favorites
    private var favoritesSyncSubscription: Subscription? = null
    val loaderManager = LoaderManager()
    // <-- EH

    init {
        setHasOptionsMenu(true)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_library)
    }

    override fun createPresenter(): LibraryPresenter {
        return LibraryPresenter()
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        binding = LibraryControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        adapter = LibraryAdapter(this)
        binding.libraryPager.adapter = adapter
        binding.libraryPager.pageSelections()
            .onEach {
                preferences.lastUsedCategory().set(it)
                activeCategory = it
            }
            .launchIn(scope)

        getColumnsPreferenceForCurrentOrientation().asImmediateFlow { mangaPerRow = it }
            .drop(1)
            // Set again the adapter to recalculate the covers height
            .onEach { reattachAdapter() }
            .launchIn(scope)

        if (selectedMangas.isNotEmpty()) {
            createActionModeIfNeeded()
        }

        // EXH -->
        loaderManager.loadingChangeListener = {
            binding.libraryProgress.visibility = if (it) View.VISIBLE else View.GONE
        }
        // EXH <--
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            activity?.tabs?.setupWithViewPager(binding.libraryPager)
            presenter.subscribeLibrary()
        }
    }

    override fun onDestroyView(view: View) {
        adapter?.onDestroy()
        adapter = null
        actionMode = null
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
        // EXH -->
        loaderManager.loadingChangeListener = null
        // EXH <--
        super.onDestroyView(view)
    }

    override fun createSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout): ViewGroup {
        val view = drawer.inflate(R.layout.library_drawer) as LibraryNavigationView
        navView = view
        drawer.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.END)

        navView?.onGroupClicked = { group ->
            when (group) {
                is LibraryNavigationView.FilterGroup -> onFilterChanged()
                is LibraryNavigationView.SortGroup -> onSortChanged()
                is LibraryNavigationView.DisplayGroup -> reattachAdapter()
                is LibraryNavigationView.BadgeGroup -> onBadgeChanged()
            }
        }

        return view
    }

    override fun cleanupSecondaryDrawer(drawer: androidx.drawerlayout.widget.DrawerLayout) {
        navView = null
    }

    override fun configureTabs(tabs: TabLayout) {
        with(tabs) {
            tabGravity = TabLayout.GRAVITY_CENTER
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = tabsVisibilityRelay.subscribe { visible ->
            val tabAnimator = (activity as? MainActivity)?.tabAnimator
            if (visible) {
                tabAnimator?.expand()
            } else {
                tabAnimator?.collapse()
            }
        }
    }

    override fun cleanupTabs(tabs: TabLayout) {
        tabsVisibilitySubscription?.unsubscribe()
        tabsVisibilitySubscription = null
    }

    fun onNextLibraryUpdate(categories: List<Category>, mangaMap: Map<Int, List<LibraryItem>>) {
        val view = view ?: return
        val adapter = adapter ?: return

        // Show empty view if needed
        if (mangaMap.isNotEmpty()) {
            binding.emptyView.hide()
        } else {
            binding.emptyView.show(R.string.information_empty_library)
        }

        // Get the current active category.
        val activeCat = if (adapter.categories.isNotEmpty()) {
            binding.libraryPager.currentItem
        } else {
            activeCategory
        }

        // Set the categories
        adapter.categories = categories

        // Restore active category.
        binding.libraryPager.setCurrentItem(activeCat, false)

        tabsVisibilityRelay.call(categories.size > 1)

        // Delay the scroll position to allow the view to be properly measured.
        view.post {
            if (isAttached) {
                activity?.tabs?.setScrollPosition(binding.libraryPager.currentItem, 0f, true)
            }
        }

        // Send the manga map to child fragments after the adapter is updated.
        libraryMangaRelay.call(LibraryMangaEvent(mangaMap))
    }

    /**
     * Returns a preference for the number of manga per row based on the current orientation.
     *
     * @return the preference.
     */
    private fun getColumnsPreferenceForCurrentOrientation(): Preference<Int> {
        return if (resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT) {
            preferences.portraitColumns()
        } else {
            preferences.landscapeColumns()
        }
    }

    /**
     * Called when a filter is changed.
     */
    private fun onFilterChanged() {
        presenter.requestFilterUpdate()
        activity?.invalidateOptionsMenu()
    }

    private fun onBadgeChanged() {
        presenter.requestBadgesUpdate()
    }

    /**
     * Called when the sorting mode is changed.
     */
    private fun onSortChanged() {
        activity?.invalidateOptionsMenu()
        presenter.requestSortUpdate()
    }

    /**
     * Reattaches the adapter to the view pager to recreate fragments
     */
    private fun reattachAdapter() {
        val adapter = adapter ?: return

        val position = binding.libraryPager.currentItem

        adapter.recycle = false
        binding.libraryPager.adapter = adapter
        binding.libraryPager.currentItem = position
        adapter.recycle = true
    }

    /**
     * Creates the action mode if it's not created already.
     */
    fun createActionModeIfNeeded() {
        if (actionMode == null) {
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
        }
    }

    /**
     * Destroys the action mode.
     */
    fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.library, menu)

        val reorganizeItem = menu.findItem(R.id.action_reorganize)
        reorganizeItem.isVisible = preferences.librarySortingMode().get() == LibrarySort.DRAG_AND_DROP

        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.maxWidth = Int.MAX_VALUE

        searchView.queryTextChanges()
            // Ignore events if this controller isn't at the top
            .filter { router.backstack.lastOrNull()?.controller() == this }
            .onEach {
                query = it.toString()
                searchRelay.call(query)
            }
            .launchIn(scope)

        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, true)
            searchView.clearFocus()

            // Manually trigger the search since the binding doesn't trigger for some reason
            searchRelay.call(query)
        }

        searchItem.fixExpand(onExpand = { invalidateMenuOnExpand() })

        // Mutate the filter icon because it needs to be tinted and the resource is shared.
        menu.findItem(R.id.action_filter).icon.mutate()

        menu.findItem(R.id.action_sync_favorites).isVisible = preferences.eh_isHentaiEnabled().get()
    }

    fun search(query: String) {
        this.query = query
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val navView = navView ?: return

        val filterItem = menu.findItem(R.id.action_filter)

        // Tint icon if there's a filter active
        if (navView.hasActiveFilters()) {
            val filterColor = activity!!.getResourceColor(R.attr.colorFilterActive)
            DrawableCompat.setTint(filterItem.icon, filterColor)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> expandActionViewFromInteraction = true
            R.id.action_filter -> {
                navView?.let { activity?.drawer?.openDrawer(GravityCompat.END) }
            }
            R.id.action_update_library -> {
                activity?.let {
                    if (LibraryUpdateService.start(it)) {
                        it.toast(R.string.updating_library)
                    }
                }
            }
            R.id.action_source_migration -> {
                router.pushController(MigrationController().withFadeTransaction())
            }
            // --> EXH
            R.id.action_sync_favorites -> {
                if (preferences.eh_showSyncIntro().get()) {
                    activity?.let { FavoritesIntroDialog().show(it) }
                } else {
                    presenter.favoritesSync.runSync()
                }
            }
            // <-- EXH
            R.id.action_alpha_asc -> reOrder(1)
            R.id.action_alpha_dsc -> reOrder(2)
            R.id.action_update_asc -> reOrder(3)
            R.id.action_update_dsc -> reOrder(4)
            else -> return super.onOptionsItemSelected(item)
        }

        return super.onOptionsItemSelected(item)
    }

    private fun reOrder(type: Int) {
        adapter?.categories?.getOrNull(binding.libraryPager.currentItem)?.id?.let {
            reorganizeRelay.call(it to type)
        }
    }

    /**
     * Invalidates the action mode, forcing it to refresh its content.
     */
    fun invalidateActionMode() {
        actionMode?.invalidate()
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.library_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val count = selectedMangas.size
        if (count == 0) {
            // Destroy action mode if there are no items selected.
            destroyActionModeIfNeeded()
        } else {
            mode.title = count.toString()

            menu.findItem(R.id.action_edit_cover)?.isVisible = count == 1
        }
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        return onActionItemClicked(item)
    }

    private fun onActionItemClicked(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit_cover -> handleChangeCover()
            R.id.action_move_to_category -> showChangeMangaCategoriesDialog()
            R.id.action_delete -> showDeleteMangaDialog()
            R.id.action_select_all -> selectAllCategoryManga()
            R.id.action_select_inverse -> selectInverseCategoryManga()
            R.id.action_migrate -> {
                val skipPre = preferences.skipPreMigration().get()
                PreMigrationController.navigateToMigration(skipPre, router, selectedMangas.mapNotNull { it.id })
                destroyActionModeIfNeeded()
            }
            else -> return false
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        // Clear all the manga selections and notify child views.
        selectedMangas.clear()
        selectionRelay.call(LibrarySelectionEvent.Cleared())
        actionMode = null
    }

    fun openManga(manga: Manga) {
        // Notify the presenter a manga is being opened.
        presenter.onOpenManga()

        router.pushController(MangaController(manga).withFadeTransaction())
    }

    /**
     * Sets the selection for a given manga.
     *
     * @param manga the manga whose selection has changed.
     * @param selected whether it's now selected or not.
     */
    fun setSelection(manga: Manga, selected: Boolean) {
        if (selected) {
            if (selectedMangas.add(manga)) {
                selectionRelay.call(LibrarySelectionEvent.Selected(manga))
            }
        } else {
            if (selectedMangas.remove(manga)) {
                selectionRelay.call(LibrarySelectionEvent.Unselected(manga))
            }
        }
    }

    /**
     * Toggles the current selection state for a given manga.
     *
     * @param manga the manga whose selection to change.
     */
    fun toggleSelection(manga: Manga) {
        if (selectedMangas.add(manga)) {
            selectionRelay.call(LibrarySelectionEvent.Selected(manga))
        } else if (selectedMangas.remove(manga)) {
            selectionRelay.call(LibrarySelectionEvent.Unselected(manga))
        }
    }

    private fun handleChangeCover() {
        val manga = selectedMangas.firstOrNull() ?: return

        if (manga.hasCustomCover(coverCache)) {
            showEditCoverDialog(manga)
        } else {
            openMangaCoverPicker(manga)
        }
    }

    /**
     * Edit custom cover for selected manga.
     */
    private fun showEditCoverDialog(manga: Manga) {
        ChangeMangaCoverDialog(this, manga).showDialog(router)
    }

    /**
     * Move the selected manga to a list of categories.
     */
    private fun showChangeMangaCategoriesDialog() {
        // Create a copy of selected manga
        val mangas = selectedMangas.toList()

        // Hide the default category because it has a different behavior than the ones from db.
        val categories = presenter.categories.filter { it.id != 0 }

        // Get indexes of the common categories to preselect.
        val commonCategoriesIndexes = presenter.getCommonCategories(mangas)
            .map { categories.indexOf(it) }
            .toTypedArray()

        ChangeMangaCategoriesDialog(this, mangas, categories, commonCategoriesIndexes)
            .showDialog(router)
    }

    private fun showDeleteMangaDialog() {
        DeleteLibraryMangasDialog(this, selectedMangas.toList()).showDialog(router)
    }

    override fun openMangaCoverPicker(manga: Manga) {
        selectedCoverManga = manga

        if (manga.favorite) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(
                Intent.createChooser(
                    intent,
                    resources?.getString(R.string.file_select_cover)
                ),
                REQUEST_IMAGE_OPEN
            )
        } else {
            activity?.toast(R.string.notification_first_add_to_library)
        }

        destroyActionModeIfNeeded()
    }

    override fun deleteMangaCover(manga: Manga) {
        presenter.deleteCustomCover(manga)
        destroyActionModeIfNeeded()
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        presenter.moveMangasToCategories(categories, mangas)
        destroyActionModeIfNeeded()
    }

    override fun deleteMangasFromLibrary(mangas: List<Manga>, deleteChapters: Boolean) {
        presenter.removeMangaFromLibrary(mangas, deleteChapters)
        destroyActionModeIfNeeded()
    }

    override fun onAttach(view: View) {
        super.onAttach(view)

        // --> EXH
        cleanupSyncState()
        favoritesSyncSubscription =
            presenter.favoritesSync.status
                .sample(100, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    updateSyncStatus(it)
                }
        // <-- EXH
    }

    override fun onDetach(view: View) {
        super.onDetach(view)

        // EXH
        cleanupSyncState()
    }

    // --> EXH
    private fun cleanupSyncState() {
        favoritesSyncSubscription?.unsubscribe()
        favoritesSyncSubscription = null
        // Close sync status
        favSyncDialog?.dismiss()
        favSyncDialog = null
        oldSyncStatus = null
        // Clear flags
        releaseSyncLocks()
    }

    private fun buildDialog() = activity?.let {
        MaterialDialog(it)
    }

    private fun showSyncProgressDialog() {
        favSyncDialog?.dismiss()
        favSyncDialog = buildDialog()
            ?.title(text = "Favorites syncing")
            ?.cancelable(false)
        // ?.progress(true, 0)
        favSyncDialog?.show()
    }

    private fun takeSyncLocks() {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releaseSyncLocks() {
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateSyncStatus(status: FavoritesSyncStatus) {
        when (status) {
            is FavoritesSyncStatus.Idle -> {
                releaseSyncLocks()

                favSyncDialog?.dismiss()
                favSyncDialog = null
            }
            is FavoritesSyncStatus.BadLibraryState.MangaInMultipleCategories -> {
                releaseSyncLocks()

                favSyncDialog?.dismiss()
                favSyncDialog = buildDialog()
                    ?.title(text = "Favorites sync error")
                    ?.message(text = status.message + " Sync will not start until the gallery is in only one category.")
                    ?.cancelable(false)
                    ?.positiveButton(text = "Show gallery") {
                        openManga(status.manga)
                        presenter.favoritesSync.status.onNext(FavoritesSyncStatus.Idle())
                    }
                    ?.negativeButton(android.R.string.ok) {
                        presenter.favoritesSync.status.onNext(FavoritesSyncStatus.Idle())
                    }
                favSyncDialog?.show()
            }
            is FavoritesSyncStatus.Error -> {
                releaseSyncLocks()

                favSyncDialog?.dismiss()
                favSyncDialog = buildDialog()
                    ?.title(text = "Favorites sync error")
                    ?.message(text = "An error occurred during the sync process: ${status.message}")
                    ?.cancelable(false)
                    ?.positiveButton(android.R.string.ok) {
                        presenter.favoritesSync.status.onNext(FavoritesSyncStatus.Idle())
                    }
                favSyncDialog?.show()
            }
            is FavoritesSyncStatus.CompleteWithErrors -> {
                releaseSyncLocks()

                favSyncDialog?.dismiss()
                favSyncDialog = buildDialog()
                    ?.title(text = "Favorites sync complete with errors")
                    ?.message(text = "Errors occurred during the sync process that were ignored:\n${status.message}")
                    ?.cancelable(false)
                    ?.positiveButton(android.R.string.ok) {
                        presenter.favoritesSync.status.onNext(FavoritesSyncStatus.Idle())
                    }
                favSyncDialog?.show()
            }
            is FavoritesSyncStatus.Processing,
            is FavoritesSyncStatus.Initializing -> {
                takeSyncLocks()

                if (favSyncDialog == null || (
                    oldSyncStatus != null &&
                        oldSyncStatus !is FavoritesSyncStatus.Initializing &&
                        oldSyncStatus !is FavoritesSyncStatus.Processing
                    )
                ) {
                    showSyncProgressDialog()
                }

                favSyncDialog?.message(text = status.message)
            }
        }
        oldSyncStatus = status
    }

    override fun startReading(manga: Manga, adapter: LibraryCategoryAdapter) {
        if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            toggleSelection(manga)
            return
        }
        val activity = activity ?: return
        val chapter = presenter.getFirstUnread(manga) ?: return
        val intent = ReaderActivity.newIntent(activity, manga, chapter)
        destroyActionModeIfNeeded()
        startActivity(intent)
    }
    // <-- EXH

    private fun selectAllCategoryManga() {
        adapter?.categories?.getOrNull(binding.libraryPager.currentItem)?.id?.let {
            selectAllRelay.call(it)
        }
    }

    private fun selectInverseCategoryManga() {
        adapter?.categories?.getOrNull(binding.libraryPager.currentItem)?.id?.let {
            selectInverseRelay.call(it)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_IMAGE_OPEN) {
            val dataUri = data?.data
            if (dataUri == null || resultCode != Activity.RESULT_OK) return
            val activity = activity ?: return
            val manga = selectedCoverManga ?: return

            selectedCoverManga = null
            presenter.editCover(manga, activity, dataUri)
        }
    }

    fun onSetCoverSuccess() {
        activity?.toast(R.string.cover_updated)
    }

    fun onSetCoverError(error: Throwable) {
        activity?.toast(R.string.notification_cover_update_failed)
        Timber.e(error)
    }

    private companion object {
        /**
         * Key to change the cover of a manga in [onActivityResult].
         */
        const val REQUEST_IMAGE_OPEN = 101
    }
}

object HeightTopWindowInsetsListener : View.OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(v: View, insets: WindowInsets): WindowInsets {
        val topInset = insets.systemWindowInsetTop
        v.setPadding(0, topInset, 0, 0)
        if (v.layoutParams.height != topInset) {
            v.layoutParams.height = topInset
            v.requestLayout()
        }
        return insets
    }
}
