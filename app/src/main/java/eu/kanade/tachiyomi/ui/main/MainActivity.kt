package eu.kanade.tachiyomi.ui.main

import android.animation.ObjectAnimator
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.databinding.MainActivityBinding
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.SecondaryDrawerController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.extension.ExtensionController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.recent.history.HistoryController
import eu.kanade.tachiyomi.ui.recent.updates.UpdatesController
import eu.kanade.tachiyomi.ui.setting.SettingsMainController
import eu.kanade.tachiyomi.ui.source.SourceController
import eu.kanade.tachiyomi.ui.source.global_search.GlobalSearchController
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.vibrate
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import exh.EXHMigrations
import exh.eh.EHentaiUpdateWorker
import exh.uconfig.WarnConfigureDialogController
import exh.ui.batchadd.BatchAddController
import exh.ui.lock.LockActivityDelegate
import exh.ui.lock.LockController
import exh.ui.lock.lockEnabled
import java.util.Date
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : BaseActivity() {

    private lateinit var router: Router

    private var drawerArrow: DrawerArrowDrawable? = null

    private var secondaryDrawer: ViewGroup? = null

    private val startScreenId by lazy {
        when (preferences.startScreen()) {
            2 -> R.id.nav_drawer_history
            3 -> R.id.nav_drawer_updates
            else -> R.id.nav_drawer_library
        }
    }

    lateinit var tabAnimator: TabsAnimator

    private lateinit var binding: MainActivityBinding

    // Idle-until-urgent
    private var firstPaint = false
    private val iuuQueue = LinkedList<() -> Unit>()

    private fun initWhenIdle(task: () -> Unit) {
        // Avoid sync issues by enforcing main thread
        if (Looper.myLooper() != Looper.getMainLooper())
            throw IllegalStateException("Can only be called on main thread!")

        if (firstPaint) {
            task()
        } else {
            iuuQueue += task
        }
    }

    override fun onResume() {
        super.onResume()
        getExtensionUpdates()
        LockActivityDelegate.onResume(this, router)
        if (!firstPaint) {
            binding.drawer.postDelayed({
                if (!firstPaint) {
                    firstPaint = true
                    iuuQueue.forEach { it() }
                }
            }, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setContentView(R.layout.main_activity)

        setSupportActionBar(binding.toolbar)

        drawerArrow = DrawerArrowDrawable(this)
        drawerArrow?.color = getResourceColor(R.attr.colorOnPrimary)
        binding.toolbar.navigationIcon = drawerArrow

        tabAnimator = TabsAnimator(binding.tabs)

        // Set behavior of Navigation drawer
        binding.navView.setNavigationItemSelectedListener { item ->
            val id = item.itemId

            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_drawer_library -> setRoot(LibraryController(), id)
                    R.id.nav_drawer_updates -> setRoot(UpdatesController(), id)
                    R.id.nav_drawer_history -> setRoot(HistoryController(), id)
                    R.id.nav_drawer_sources -> setRoot(SourceController(), id)
                    R.id.nav_drawer_extensions -> setRoot(ExtensionController(), id)
                    // --> EXH
                    R.id.nav_drawer_batch_add -> setRoot(BatchAddController(), id)
                    // <-- EHX 
                    R.id.nav_drawer_downloads -> {
                        router.pushController(DownloadController().withFadeTransaction())
                    }
                    R.id.nav_drawer_settings -> {
                        router.pushController(SettingsMainController().withFadeTransaction())
                    }
                }
            }
            binding.drawer.closeDrawer(GravityCompat.START)
            true
        }

        val container: ViewGroup = findViewById(R.id.controller_container)

        router = Conductor.attachRouter(this, container, savedInstanceState)
        if (!router.hasRootController()) {
            // Set start screen
            if (!handleIntentAction(intent)) {
                setSelectedDrawerItem(startScreenId)
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            if (router.backstackSize == 1) {
                binding.drawer.openDrawer(GravityCompat.START)
            } else {
                onBackPressed()
            }
        }

        router.addChangeListener(object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {

                syncActivityViewWithController(to, from)
            }

            override fun onChangeCompleted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: ViewGroup,
                handler: ControllerChangeHandler
            ) {
            }
        })

        // --> EH
        initWhenIdle {
            // Hook long press hamburger menu to lock
            getToolbarNavigationIcon(binding.toolbar)?.setOnLongClickListener {
                if (lockEnabled(preferences)) {
                    LockActivityDelegate.doLock(router, true)
                    vibrate(50)
                    true
                } else false
            }
        }

        // Show lock
        if (savedInstanceState == null) {
            if (lockEnabled(preferences)) {
                // Special case first lock
                LockActivityDelegate.doLock(router)
            }
        }
        // <-- EH

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        if (savedInstanceState == null) {
            // Show changelog if needed
            // TODO
//            if (Migrations.upgrade(preferences)) {
//                ChangelogDialogController().showDialog(router)
//            }

            // EXH -->
            // Perform EXH specific migrations
            if (EXHMigrations.upgrade(preferences)) {
                ChangelogDialogController().showDialog(router)
            }

            initWhenIdle {
                // Upload settings
                if (preferences.enableExhentai().getOrDefault() &&
                        preferences.eh_showSettingsUploadWarning().getOrDefault())
                    WarnConfigureDialogController.uploadSettings(router)

                // Scheduler uploader job if required
                EHentaiUpdateWorker.scheduleBackground(this)
            }
            // EXH <--
        }
        preferences.extensionUpdatesCount().asObservable().subscribe {
            setExtensionsBadge()
        }
        setExtensionsBadge()
    }

    override fun onNewIntent(intent: Intent) {
        if (!handleIntentAction(intent)) {
            super.onNewIntent(intent)
        }
    }

    private fun setExtensionsBadge() {
        val extUpdateText: TextView = binding.navView.menu.findItem(
                R.id.nav_drawer_extensions
        )?.actionView as? TextView ?: return

        val updates = preferences.extensionUpdatesCount().getOrDefault()
        if (updates > 0) {
            extUpdateText.text = updates.toString()
            extUpdateText.visible()
        } else {
            extUpdateText.text = null
            extUpdateText.gone()
        }
    }

    private fun getExtensionUpdates() {
        // Limit checks to once a day at most
        val now = Date().time
        if (now < preferences.lastExtCheck().getOrDefault() + TimeUnit.DAYS.toMillis(1)) {
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val pendingUpdates = ExtensionGithubApi().checkForUpdates(this@MainActivity)
                preferences.extensionUpdatesCount().set(pendingUpdates.size)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun handleIntentAction(intent: Intent): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(applicationContext, notificationId, intent.getIntExtra("groupId", 0))
        }

        when (intent.action) {
            SHORTCUT_LIBRARY -> setSelectedDrawerItem(R.id.nav_drawer_library)
            SHORTCUT_RECENTLY_UPDATED -> setSelectedDrawerItem(R.id.nav_drawer_updates)
            SHORTCUT_RECENTLY_READ -> setSelectedDrawerItem(R.id.nav_drawer_history)
            SHORTCUT_CATALOGUES -> setSelectedDrawerItem(R.id.nav_drawer_sources)
            SHORTCUT_EXTENSIONS -> setSelectedDrawerItem(R.id.nav_drawer_extensions)
            SHORTCUT_MANGA -> {
                val extras = intent.extras ?: return false
                router.setRoot(RouterTransaction.with(MangaController(extras)))
            }
            SHORTCUT_DOWNLOADS -> {
                if (router.backstack.none { it.controller() is DownloadController }) {
                    setSelectedDrawerItem(R.id.nav_drawer_downloads)
                }
            }
            Intent.ACTION_SEARCH, "com.google.android.gms.actions.SEARCH_ACTION" -> {
                // If the intent match the "standard" Android search intent
                // or the Google-specific search intent (triggered by saying or typing "search *query* on *Tachiyomi*" in Google Search/Google Assistant)

                // Get the search query provided in extras, and if not null, perform a global search with it.
                val query = intent.getStringExtra(SearchManager.QUERY)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(GlobalSearchController(query).withFadeTransaction())
                }
            }
            INTENT_SEARCH -> {
                val query = intent.getStringExtra(INTENT_SEARCH_QUERY)
                val filter = intent.getStringExtra(INTENT_SEARCH_FILTER)
                if (query != null && query.isNotEmpty()) {
                    if (router.backstackSize > 1) {
                        router.popToRoot()
                    }
                    router.pushController(GlobalSearchController(query, filter).withFadeTransaction())
                }
            }
            else -> return false
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.navView?.setNavigationItemSelectedListener(null)
        binding.toolbar?.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        val backstackSize = router.backstackSize
        if (binding.drawer.isDrawerOpen(GravityCompat.START) || binding.drawer.isDrawerOpen(GravityCompat.END)) {
            binding.drawer.closeDrawers()
        } else if (backstackSize == 1 && router.getControllerWithTag("$startScreenId") == null) {
            setSelectedDrawerItem(startScreenId)
        } else if (backstackSize == 1 || !router.handleBack()) {
            super.onBackPressed()
        }
    }

    private fun setSelectedDrawerItem(itemId: Int) {
        if (!isFinishing) {
            binding.navView.setCheckedItem(itemId)
            binding.navView.menu.performIdentifierAction(itemId, 0)
        }
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    fun getToolbarNavigationIcon(toolbar: Toolbar): View? {
        try {
            // check if contentDescription previously was set
            val hadContentDescription = !TextUtils.isEmpty(toolbar.navigationContentDescription)
            val contentDescription = if (!hadContentDescription) toolbar.navigationContentDescription else "navigationIcon"
            toolbar.navigationContentDescription = contentDescription

            val potentialViews = ArrayList<View>()

            // find the view based on it's content description, set programmatically or with android:contentDescription
            toolbar.findViewsWithText(potentialViews, contentDescription, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)

            // Nav icon is always instantiated at this point because calling setNavigationContentDescription ensures its existence
            val navIcon = potentialViews.firstOrNull()

            // Clear content description if not previously present
            if (!hadContentDescription)
                toolbar.navigationContentDescription = null
            return navIcon
        } catch (t: Throwable) {
            Timber.w(t, "Could not find toolbar nav icon!")
            return null
        }
    }

    private fun syncActivityViewWithController(to: Controller?, from: Controller? = null) {
        if (from is DialogController || to is DialogController) {
            return
        }

        val showHamburger = router.backstackSize == 1
        if (showHamburger) {
            binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        } else {
            binding.drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        }

        // --> EH
        // Special case and hide drawer arrow for lock controller
        if (to is LockController) {
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            binding.toolbar.navigationIcon = null
        } else {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            binding.toolbar.navigationIcon = drawerArrow
        }
        // <-- EH

        ObjectAnimator.ofFloat(drawerArrow, "progress", if (showHamburger) 0f else 1f).start()

        if (from is TabbedController) {
            from.cleanupTabs(binding.tabs)
        }
        if (to is TabbedController) {
            tabAnimator.expand()
            to.configureTabs(binding.tabs)
        } else {
            tabAnimator.collapse()
            binding.tabs.setupWithViewPager(null)
        }

        if (from is SecondaryDrawerController) {
            if (secondaryDrawer != null) {
                from.cleanupSecondaryDrawer(binding.drawer)
                binding.drawer.removeView(secondaryDrawer)
                secondaryDrawer = null
            }
        }
        if (to is SecondaryDrawerController) {
            secondaryDrawer = to.createSecondaryDrawer(binding.drawer)?.also { binding.drawer.addView(it) }
        }

        if (to is NoToolbarElevationController) {
            binding.appbar.disableElevation()
        } else {
            binding.appbar.enableElevation()
        }
    }

    companion object {
        // Shortcut actions
        const val SHORTCUT_LIBRARY = "eu.kanade.tachiyomi.SHOW_LIBRARY"
        const val SHORTCUT_RECENTLY_UPDATED = "eu.kanade.tachiyomi.SHOW_RECENTLY_UPDATED"
        const val SHORTCUT_RECENTLY_READ = "eu.kanade.tachiyomi.SHOW_RECENTLY_READ"
        const val SHORTCUT_CATALOGUES = "eu.kanade.tachiyomi.SHOW_CATALOGUES"
        const val SHORTCUT_DOWNLOADS = "eu.kanade.tachiyomi.SHOW_DOWNLOADS"
        const val SHORTCUT_MANGA = "eu.kanade.tachiyomi.SHOW_MANGA"
        const val SHORTCUT_EXTENSIONS = "eu.kanade.tachiyomi.EXTENSIONS"

        const val INTENT_SEARCH = "eu.kanade.tachiyomi.SEARCH"
        const val INTENT_SEARCH_QUERY = "query"
        const val INTENT_SEARCH_FILTER = "filter"
    }
}
