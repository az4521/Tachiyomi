package eu.kanade.tachiyomi.ui.setting

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceScreen
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.library.LibraryUpdateService
import eu.kanade.tachiyomi.data.library.LibraryUpdateService.Target
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceManager.Companion.DELEGATED_SOURCES
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.openInBrowser
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.preference.defaultValue
import eu.kanade.tachiyomi.util.preference.editTextPreference
import eu.kanade.tachiyomi.util.preference.entriesRes
import eu.kanade.tachiyomi.util.preference.intListPreference
import eu.kanade.tachiyomi.util.preference.listPreference
import eu.kanade.tachiyomi.util.preference.onChange
import eu.kanade.tachiyomi.util.preference.onClick
import eu.kanade.tachiyomi.util.preference.preference
import eu.kanade.tachiyomi.util.preference.preferenceCategory
import eu.kanade.tachiyomi.util.preference.summaryRes
import eu.kanade.tachiyomi.util.preference.switchPreference
import eu.kanade.tachiyomi.util.preference.titleRes
import eu.kanade.tachiyomi.util.system.MiuiUtil
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.powerManager
import eu.kanade.tachiyomi.util.system.toast
import exh.EH_SOURCE_ID
import exh.EXH_SOURCE_ID
import exh.NHENTAI_SOURCE_ID
import exh.debug.SettingsDebugController
import exh.log.EHLogLevel
import exh.source.BlacklistedSources
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.injectLazy

class SettingsAdvancedController : SettingsController() {

    private val network: NetworkHelper by injectLazy()

    private val chapterCache: ChapterCache by injectLazy()

    private val db: DatabaseHelper by injectLazy()

    @SuppressLint("BatteryLife")
    override fun setupPreferenceScreen(screen: PreferenceScreen) = with(screen) {
        titleRes = R.string.pref_category_advanced

        preference {
            key = CLEAR_CACHE_KEY
            titleRes = R.string.pref_clear_chapter_cache
            summary = context.getString(R.string.used_cache, chapterCache.readableSize)

            onClick { clearChapterCache() }
        }
        preference {
            titleRes = R.string.pref_clear_cookies

            onClick {
                network.cookieManager.removeAll()
                activity?.toast(R.string.cookies_cleared)
            }
        }
        switchPreference {
            key = Keys.enableDoh
            titleRes = R.string.pref_dns_over_https
            summaryRes = R.string.pref_dns_over_https_summary
            defaultValue = false
        }
        preference {
            titleRes = R.string.pref_clear_database
            summaryRes = R.string.pref_clear_database_summary

            onClick {
                val ctrl = ClearDatabaseDialogController()
                ctrl.targetController = this@SettingsAdvancedController
                ctrl.showDialog(router)
            }
        }
        preference {
            titleRes = R.string.pref_refresh_library_metadata

            onClick { LibraryUpdateService.start(context, target = Target.DETAILS) }
        }
        preference {
            titleRes = R.string.pref_refresh_library_covers

            onClick { LibraryUpdateService.start(context, target = Target.COVERS) }
        }
        preference {
            titleRes = R.string.pref_refresh_library_tracking
            summaryRes = R.string.pref_refresh_library_tracking_summary

            onClick { LibraryUpdateService.start(context, target = Target.TRACKING) }
        }
        preference {
            key = "dump_crash_logs"
            titleRes = R.string.pref_dump_crash_logs
            summaryRes = R.string.pref_dump_crash_logs_summary

            onClick {
                CrashLogUtil(context).dumpLogs()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            preference {
                titleRes = R.string.pref_disable_battery_optimization
                summaryRes = R.string.pref_disable_battery_optimization_summary

                onClick {
                    val packageName: String = context.packageName
                    if (!context.powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        try {
                            val intent = Intent().apply {
                                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            context.toast(R.string.battery_optimization_setting_activity_not_found)
                        }
                    } else {
                        context.toast(R.string.battery_optimization_disabled)
                    }
                }
            }
        }

        editTextPreference {
            key = Keys.defaultUserAgent
            titleRes = R.string.pref_user_agent_string
            text = preferences.defaultUserAgent().get()
            summary = network.defaultUserAgent

            onChange {
                if (it.toString().isBlank()) {
                    activity?.toast(R.string.error_user_agent_string_blank)
                    false
                } else {
                    activity?.toast(R.string.requires_app_restart)
                    true
                }
            }
        }
        if (preferences.defaultUserAgent().isSet()) {
            preference {
                key = "pref_reset_user_agent"
                titleRes = R.string.pref_reset_user_agent_string

                onClick {
                    preferences.defaultUserAgent().delete()
                    activity?.toast(R.string.requires_app_restart)
                }
            }
        }

        // <-- EXH

        preferenceCategory {
            titleRes = R.string.label_extensions

            listPreference {
                key = Keys.extensionInstaller
                titleRes = R.string.ext_installer_pref
                summary = "%s"
                entriesRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    arrayOf(
                        R.string.ext_installer_legacy,
                        R.string.ext_installer_packageinstaller,
                        R.string.ext_installer_shizuku
                    )
                } else {
                    arrayOf(
                        R.string.ext_installer_legacy,
                        R.string.ext_installer_packageinstaller
                    )
                }
                entryValues = PreferenceValues.ExtensionInstaller.values().map { it.name }.toTypedArray()
                defaultValue = if (MiuiUtil.isMiui() || (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)) {
                    PreferenceValues.ExtensionInstaller.LEGACY
                } else {
                    PreferenceValues.ExtensionInstaller.PACKAGEINSTALLER
                }.name

                onChange {
                    if (it == PreferenceValues.ExtensionInstaller.SHIZUKU.name &&
                        !context.isPackageInstalled("moe.shizuku.privileged.api")
                    ) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.ext_installer_shizuku)
                            .setMessage(R.string.ext_installer_shizuku_unavailable_dialog)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                openInBrowser("https://shizuku.rikka.app/download")
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        false
                    } else {
                        true
                    }
                }
            }
        }

        preferenceCategory {
            title = "Developer tools"
            isPersistent = false

            switchPreference {
                title = "Enable integrated hentai features"
                summary = "This is a experimental feature that will disable all hentai features if toggled off"
                key = Keys.eh_is_hentai_enabled
                defaultValue = true

                onChange {
                    if (preferences.eh_isHentaiEnabled().get()) {
                        if (EH_SOURCE_ID !in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES += EH_SOURCE_ID
                        }
                        if (EXH_SOURCE_ID !in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES += EXH_SOURCE_ID
                        }
                        if (NHENTAI_SOURCE_ID !in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES += NHENTAI_SOURCE_ID
                        }
                    } else {
                        if (EH_SOURCE_ID in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES -= EH_SOURCE_ID
                        }
                        if (EXH_SOURCE_ID in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES -= EXH_SOURCE_ID
                        }
                        if (NHENTAI_SOURCE_ID in BlacklistedSources.HIDDEN_SOURCES) {
                            BlacklistedSources.HIDDEN_SOURCES -= NHENTAI_SOURCE_ID
                        }
                    }
                    true
                }
            }

            switchPreference {
                title = "Enable delegated sources"
                key = Keys.eh_delegateSources
                defaultValue = true
                summary = "Apply ${context.getString(R.string.app_name)} enhancements to the following sources if they are installed: ${DELEGATED_SOURCES.values.map { it.sourceName }.distinct().joinToString()}"
            }

            preference {
                titleRes = R.string.pref_clear_history
                summaryRes = R.string.pref_clear_history_summary

                onClick {
                    val ctrl = ClearHistoryDialogController()
                    ctrl.targetController = this@SettingsAdvancedController
                    ctrl.showDialog(router)
                }
            }

            intListPreference {
                key = Keys.eh_logLevel
                title = "Log level"

                entries = EHLogLevel.values().map {
                    "${it.name.toLowerCase().capitalize()} (${it.description})"
                }.toTypedArray()
                entryValues = EHLogLevel.values().mapIndexed { index, _ -> "$index" }.toTypedArray()
                defaultValue = "0"

                summary = "Changing this can impact app performance. Force-restart app after changing. Current value: %s"
            }

            switchPreference {
                title = "Enable source blacklist"
                key = Keys.eh_enableSourceBlacklist
                defaultValue = true
                summary = "Hide extensions/sources that are incompatible with ${context.getString(R.string.app_name)}. Force-restart app after changing."
            }

            preference {
                title = "Open debug menu"
                summary = HtmlCompat.fromHtml("DO NOT TOUCH THIS MENU UNLESS YOU KNOW WHAT YOU ARE DOING! <font color='red'>IT CAN CORRUPT YOUR LIBRARY!</font>", HtmlCompat.FROM_HTML_MODE_LEGACY)
                onClick { router.pushController(SettingsDebugController().withFadeTransaction()) }
            }
        }
        // <-- EXH
    }

    private fun clearChapterCache() {
        if (activity == null) return
        val files = chapterCache.cacheDir.listFiles() ?: return

        var deletedFiles = 0

        Observable.defer { Observable.from(files) }
            .doOnNext { file ->
                if (chapterCache.removeFileFromCache(file.name)) {
                    deletedFiles++
                }
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .doOnError {
                activity?.toast(R.string.cache_delete_error)
            }
            .doOnCompleted {
                activity?.toast(resources?.getString(R.string.cache_deleted, deletedFiles))
                findPreference(CLEAR_CACHE_KEY)?.summary =
                    resources?.getString(R.string.used_cache, chapterCache.readableSize)
            }
            .subscribe()
    }

    class ClearDatabaseDialogController : DialogController() {
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .message(R.string.clear_database_confirmation)
                .positiveButton(android.R.string.ok) {
                    (targetController as? SettingsAdvancedController)?.clearDatabase()
                }
                .negativeButton(android.R.string.cancel)
        }
    }

    class ClearHistoryDialogController : DialogController() {
        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            return MaterialDialog(activity!!)
                .message(R.string.clear_history_confirmation)
                .positiveButton(android.R.string.ok) {
                    (targetController as? SettingsAdvancedController)?.clearHistory()
                }
                .negativeButton(android.R.string.cancel)
        }
    }

    private fun clearHistory() {
        db.deleteHistory().executeAsBlocking()
        activity?.toast(R.string.clear_history_completed)
    }

    private fun clearDatabase() {
        db.deleteMangasNotInLibrary().executeAsBlocking()
        db.deleteHistoryNoLastRead().executeAsBlocking()
        activity?.toast(R.string.clear_database_completed)
    }

    private companion object {
        const val CLEAR_CACHE_KEY = "pref_clear_cache_key"
    }
}
