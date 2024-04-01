package exh.log

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.ms_square.debugoverlay.DataObserver
import com.ms_square.debugoverlay.OverlayModule
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.system.dpToPx
import uy.kohesive.injekt.injectLazy

class EHDebugModeOverlay(private val context: Context) : OverlayModule<String>(null, null) {
    private var textView: TextView? = null
    private val prefs: PreferencesHelper by injectLazy()

    override fun start() {}

    override fun stop() {}

    override fun notifyObservers() {}

    override fun addObserver(observer: DataObserver<Any>) {
        observer.onDataAvailable(buildInfo())
    }

    override fun removeObserver(observer: DataObserver<Any>) {}

    override fun onDataAvailable(data: String?) {
        textView?.text = HtmlCompat.fromHtml(data!!, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    override fun createView(
        root: ViewGroup,
        textColor: Int,
        textSize: Float,
        textAlpha: Float
    ): View {
        val view = LinearLayout(root.context)
        view.layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        view.setPadding(4.dpToPx, 0, 4.dpToPx, 4.dpToPx)
        val textView = TextView(view.context)
        textView.setTextColor(textColor)
        textView.textSize = textSize
        textView.alpha = textAlpha
        textView.text = HtmlCompat.fromHtml(buildInfo(), HtmlCompat.FROM_HTML_MODE_LEGACY)
        textView.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        view.addView(textView)
        this.textView = textView
        return view
    }

    fun buildInfo() =
        """
        <font color='green'>===[ ${context.getString(R.string.app_name)} ]===</font><br>
        <b>Build type:</b> ${BuildConfig.BUILD_TYPE}<br>
        <b>Debug mode:</b> ${BuildConfig.DEBUG.asEnabledString()}<br>
        <b>Version code:</b> ${BuildConfig.VERSION_CODE}<br>
        <b>Commit SHA:</b> ${BuildConfig.COMMIT_SHA}<br>
        <b>Log level:</b> ${EHLogLevel.currentLogLevel.name.lowercase()}<br>
        <b>Source blacklist:</b> ${prefs.eh_enableSourceBlacklist().get().asEnabledString()}
        """.trimIndent()

    private fun Boolean.asEnabledString() = if (this) "enabled" else "disabled"
}
