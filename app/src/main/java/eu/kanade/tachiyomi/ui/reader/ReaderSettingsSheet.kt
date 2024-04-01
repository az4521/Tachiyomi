package eu.kanade.tachiyomi.ui.reader

import android.os.Build
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.Spinner
import androidx.annotation.ArrayRes
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tfcporciuncula.flow.Preference
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.databinding.ReaderSettingsSheetBinding
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.view.invisible
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.widget.IgnoreFirstSpinnerListener
import uy.kohesive.injekt.injectLazy
import com.f2prateek.rx.preferences.Preference as RxPreference

/**
 * Sheet to show reader and viewer preferences.
 */
class ReaderSettingsSheet(private val activity: ReaderActivity) : BottomSheetDialog(activity) {
    private val preferences by injectLazy<PreferencesHelper>()

    private val binding = ReaderSettingsSheetBinding.inflate(layoutInflater)

    init {
        // Use activity theme for this layout
        val view = binding.root
        val scroll = NestedScrollView(activity)
        scroll.addView(view)
        setContentView(scroll)
    }

    /**
     * Called when the sheet is created. It initializes the listeners and values of the preferences.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initGeneralPreferences()

        when (activity.viewer) {
            is PagerViewer -> initPagerPreferences()
            is WebtoonViewer -> initWebtoonPreferences()
        }
    }

    /**
     * Init general reader preferences.
     */
    private fun initGeneralPreferences() {
        binding.viewer.onItemSelectedListener =
            IgnoreFirstSpinnerListener { position ->
                activity.presenter.setMangaViewer(position)

                val mangaViewer = activity.presenter.getMangaViewer()
                if (mangaViewer == ReaderActivity.WEBTOON || mangaViewer == ReaderActivity.VERTICAL_PLUS || mangaViewer == ReaderActivity.WEBTOON_HORIZ_LTR || mangaViewer == ReaderActivity.WEBTOON_HORIZ_RTL) {
                    initWebtoonPreferences()
                } else {
                    initPagerPreferences()
                }
            }
        binding.viewer.setSelection(activity.presenter.manga?.viewer ?: 0, false)

        binding.rotationMode.bindToPreference(preferences.rotation(), 1)
        binding.backgroundColor.bindToIntPreference(preferences.readerTheme(), R.array.reader_themes_values)
        binding.showPageNumber.bindToPreference(preferences.showPageNumber())
        binding.fullscreen.bindToPreference(preferences.fullscreen())

        val hasDisplayCutout =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                activity.window?.decorView?.rootWindowInsets?.displayCutout != null
        if (hasDisplayCutout) {
            binding.cutoutShort.visible()
            binding.cutoutShort.bindToPreference(preferences.cutoutShort())
        }

        binding.keepscreen.bindToPreference(preferences.keepScreenOn())
        binding.longTap.bindToPreference(preferences.readWithLongTap())
        binding.alwaysShowChapterTransition.bindToPreference(preferences.alwaysShowChapterTransition())
        binding.autoWebtoonMode.bindToPreference(preferences.eh_useAutoWebtoon())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.trueColor.visible()
            binding.trueColor.bindToPreference(preferences.trueColor())
        }
    }

    /**
     * Init the preferences for the pager reader.
     */
    private fun initPagerPreferences() {
        binding.webtoonPrefsGroup.invisible()
        binding.pagerPrefsGroup.visible()

        binding.scaleType.bindToPreference(preferences.imageScaleType(), 1)
        binding.zoomStart.bindToPreference(preferences.zoomStart(), 1)
        binding.cropBorders.bindToPreference(preferences.cropBorders())
        binding.pageTransitions.bindToPreference(preferences.pageTransitions())
    }

    /**
     * Init the preferences for the webtoon reader.
     */
    private fun initWebtoonPreferences() {
        binding.pagerPrefsGroup.invisible()
        binding.webtoonPrefsGroup.visible()

        binding.cropBordersWebtoon.bindToPreference(preferences.cropBordersWebtoon())
        binding.webtoonSidePadding.bindToIntPreference(preferences.webtoonSidePadding(), R.array.webtoon_side_padding_values)
    }

    /**
     * Binds a checkbox or switch view with a boolean preference.
     */
    private fun CompoundButton.bindToPreference(pref: Preference<Boolean>) {
        isChecked = pref.get()
        setOnCheckedChangeListener { _, isChecked -> pref.set(isChecked) }
    }

    /**
     * Binds a spinner to an int preference with an optional offset for the value.
     */
    private fun Spinner.bindToPreference(
        pref: RxPreference<Int>,
        offset: Int = 0
    ) {
        onItemSelectedListener =
            IgnoreFirstSpinnerListener { position ->
                pref.set(position + offset)
            }
        setSelection(pref.getOrDefault() - offset, false)
    }

    /**
     * Binds a spinner to an int preference with an optional offset for the value.
     */
    private fun Spinner.bindToPreference(
        pref: Preference<Int>,
        offset: Int = 0
    ) {
        onItemSelectedListener =
            IgnoreFirstSpinnerListener { position ->
                pref.set(position + offset)
            }
        setSelection(pref.get() - offset, false)
    }

    /**
     * Binds a spinner to an int preference. The position of the spinner item must
     * correlate with the [intValues] resource item (in arrays.xml), which is a <string-array>
     * of int values that will be parsed here and applied to the preference.
     */
    private fun Spinner.bindToIntPreference(
        pref: Preference<Int>,
        @ArrayRes intValuesResource: Int
    ) {
        val intValues = resources.getStringArray(intValuesResource).map { it.toIntOrNull() }
        onItemSelectedListener =
            IgnoreFirstSpinnerListener { position ->
                pref.set(intValues[position]!!)
            }
        setSelection(intValues.indexOf(pref.get()), false)
    }
}
