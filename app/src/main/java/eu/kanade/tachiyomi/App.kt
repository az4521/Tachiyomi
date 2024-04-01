package eu.kanade.tachiyomi

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import com.elvishew.xlog.LogConfiguration
import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import com.elvishew.xlog.printer.AndroidPrinter
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.FilePrinter
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator
import com.github.ajalt.reprint.core.Reprint
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.security.ProviderInstaller
import com.kizitonwose.time.days
import com.ms_square.debugoverlay.DebugOverlay
import com.ms_square.debugoverlay.modules.FpsModule
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.ForceCloseActivity
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.WebViewUtil
import eu.kanade.tachiyomi.util.system.toast
import exh.debug.DebugToggles
import exh.log.CrashlyticsPrinter
import exh.log.EHDebugModeOverlay
import exh.log.EHLogLevel
import exh.ui.lock.LockActivityDelegate
import io.realm.Realm
import io.realm.RealmConfiguration
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.conscrypt.Conscrypt
import timber.log.Timber
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.security.NoSuchAlgorithmException
import java.security.Security
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

open class App : Application(), LifecycleObserver {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        setupExhLogging() // EXH logging

        workaroundAndroid7BrokenSSL()

        // Debug tool; see https://fbflipper.com/
        // SoLoader.init(this, false)
        // if (BuildConfig.DEBUG && FlipperUtils.shouldEnableFlipper(this)) {
        //     val client = AndroidFlipperClient.getInstance(this)
        //     client.addPlugin(InspectorFlipperPlugin(this, DescriptorMapping.withDefaults()))
        //     client.addPlugin(DatabasesFlipperPlugin(this))
        //     client.start()
        // }

        // Enforce WebView availability
        if (!WebViewUtil.supportsWebView(this)) {
            toast(R.string.information_webview_required, Toast.LENGTH_LONG)
            ForceCloseActivity.closeApp(this)
        }

        // TLS 1.3 support for Android 10 and below
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        Injekt = InjektScope(DefaultRegistrar())
        Injekt.importModule(AppModule(this))

        setupNotificationChannels()
        Realm.init(this)
        GlobalScope.launch { deleteOldMetadataRealm() } // Delete old metadata DB (EH)
        Reprint.initialize(this) // Setup fingerprint (EH)
        if ((BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "releaseTest") && DebugToggles.ENABLE_DEBUG_OVERLAY.enabled) {
            setupDebugOverlay()
        }

        LocaleHelper.updateConfiguration(this, resources.configuration)

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        // App in background
        LockActivityDelegate.willLock = true
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        LocaleHelper.updateConfiguration(this, newConfig, true)
    }

    private fun workaroundAndroid7BrokenSSL() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N ||
            Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1
        ) {
            try {
                SSLContext.getInstance("TLSv1.2")
            } catch (e: NoSuchAlgorithmException) {
                XLog.e("Could not install Android 7 broken SSL workaround!", e)
            }

            try {
                ProviderInstaller.installIfNeeded(applicationContext)
            } catch (e: GooglePlayServicesRepairableException) {
                XLog.e("Could not install Android 7 broken SSL workaround!", e)
            } catch (e: GooglePlayServicesNotAvailableException) {
                XLog.e("Could not install Android 7 broken SSL workaround!", e)
            }
        }
    }

    override fun getPackageName(): String {
        try {
            // Override the value passed as X-Requested-With in WebView requests
            val stackTrace = Thread.currentThread().stackTrace
            for (element in stackTrace) {
                if ("org.chromium.base.BuildInfo".equals(element.className, ignoreCase = true)) {
                    if ("getAll".equals(element.methodName, ignoreCase = true)) {
                        return WebViewUtil.SPOOF_PACKAGE_NAME
                    }
                    break
                }
            }
        } catch (e: Exception) {
        }
        return super.getPackageName()
    }

    protected open fun setupNotificationChannels() {
        Notifications.createChannels(this)
    }

    // EXH
    private fun deleteOldMetadataRealm() {
        val config =
            RealmConfiguration.Builder()
                .name("gallery-metadata.realm")
                .schemaVersion(3)
                .deleteRealmIfMigrationNeeded()
                .build()
        Realm.deleteRealm(config)

        // Delete old paper db files
        listOf(
            File(filesDir, "gallery-ex"),
            File(filesDir, "gallery-perveden"),
            File(filesDir, "gallery-nhentai")
        ).forEach {
            if (it.exists()) {
                thread {
                    it.deleteRecursively()
                }
            }
        }
    }

    // EXH
    private fun setupExhLogging() {
        EHLogLevel.init(this)

        val logLevel =
            if (EHLogLevel.shouldLog(EHLogLevel.EXTRA)) {
                LogLevel.ALL
            } else {
                LogLevel.WARN
            }

        val logConfig =
            LogConfiguration.Builder()
                .logLevel(logLevel)
                .t()
                .st(2)
                .nb()
                .build()

        val printers = mutableListOf<Printer>(AndroidPrinter())

        val logFolder =
            File(
                Environment.getExternalStorageDirectory().absolutePath + File.separator +
                    getString(R.string.app_name),
                "logs"
            )

        printers +=
            FilePrinter
                .Builder(logFolder.absolutePath)
                .fileNameGenerator(
                    object : DateFileNameGenerator() {
                        override fun generateFileName(
                            logLevel: Int,
                            timestamp: Long
                        ): String {
                            return super.generateFileName(logLevel, timestamp) + "-${BuildConfig.BUILD_TYPE}"
                        }
                    }
                )
                .cleanStrategy(FileLastModifiedCleanStrategy(7.days.inMilliseconds.longValue))
                .backupStrategy(NeverBackupStrategy())
                .build()

        // Install Crashlytics in prod
        if (!BuildConfig.DEBUG) {
            printers += CrashlyticsPrinter(LogLevel.ERROR)
        }

        XLog.init(
            logConfig,
            *printers.toTypedArray()
        )

        XLog.d("Application booting...")
    }

    // EXH
    private fun setupDebugOverlay() {
        try {
            DebugOverlay.Builder(this)
                .modules(FpsModule(), EHDebugModeOverlay(this))
                .bgColor(Color.parseColor("#7F000000"))
                .notification(false)
                .allowSystemLayer(false)
                .build()
                .install()
        } catch (e: IllegalStateException) {
            // Crashes if app is in background
            XLog.e("Failed to initialize debug overlay, app in background?", e)
        }
    }
}
