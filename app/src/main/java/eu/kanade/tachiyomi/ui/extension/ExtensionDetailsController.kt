package eu.kanade.tachiyomi.ui.extension

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.ExtensionDetailControllerBinding
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.view.visible
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.view.clicks

class ExtensionDetailsController(bundle: Bundle? = null) :
    NucleusController<ExtensionDetailControllerBinding, ExtensionDetailsPresenter>(bundle),
    FlexibleAdapter.OnItemClickListener {
    constructor(pkgName: String) : this(
        Bundle().apply {
            putString(PKGNAME_KEY, pkgName)
        }
    )

    /**
     * Adapter containing repo items.
     */
    private var adapter: ExtensionDetailsPrefsButtonAdapter? = null

    private var sources: List<ConfigurableSource>? = null

    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = ExtensionDetailControllerBinding.inflate(inflater)
        return binding.root
    }

    override fun createPresenter(): ExtensionDetailsPresenter {
        return ExtensionDetailsPresenter(args.getString(PKGNAME_KEY)!!)
    }

    override fun getTitle(): String? {
        return resources?.getString(R.string.label_extension_info)
    }

    @SuppressLint("PrivateResource")
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        val extension = presenter.extension ?: return
        val context = view.context

        binding.extensionTitle.text = extension.name
        binding.extensionVersion.text = context.getString(R.string.ext_version_info, extension.versionName)
        binding.extensionLang.text =
            context.getString(
                R.string.ext_language_info,
                LocaleHelper.getSourceDisplayName(extension.lang, context)
            )
        binding.extensionPkg.text = extension.pkgName
        extension.getApplicationIcon(context)?.let { binding.extensionIcon.setImageDrawable(it) }
        binding.extensionUninstallButton.clicks()
            .onEach { presenter.uninstallExtension() }
            .launchIn(scope)

        if (extension.isObsolete) {
            binding.extensionWarningBanner.visible()
            binding.extensionWarningBanner.setText(R.string.obsolete_extension_message)
        }

        if (extension.isUnofficial) {
            binding.extensionWarningBanner.visible()
            binding.extensionWarningBanner.setText(R.string.unofficial_extension_message)
        }

        if (extension.isRedundant) {
            binding.extensionWarningBanner.visible()
            binding.extensionWarningBanner.setText(R.string.redundant_extension_message)
        }

        adapter = ExtensionDetailsPrefsButtonAdapter(this@ExtensionDetailsController)
        binding.extensionDetailsRecycler.layoutManager = LinearLayoutManager(context)
        binding.extensionDetailsRecycler.adapter = adapter
        binding.extensionDetailsRecycler.setHasFixedSize(true)

        sources = presenter.extension?.sources?.filterIsInstance<ConfigurableSource>()

        adapter!!.updateDataSet(
            sources?.map { ExtensionDetailsPrefsButtonItem(it.toString()) }
        )
    }

    override fun onDestroyView(view: View) {
        adapter = null
        sources = null
        super.onDestroyView(view)
    }

    override fun onItemClick(
        view: View?,
        position: Int
    ): Boolean {
        val id = sources?.get(position)?.id
        return if (id != null) {
            openPreferences(id)
            true
        } else {
            false
        }
    }

    fun onExtensionUninstalled() {
        router.popCurrentController()
    }

    private fun openPreferences(sourceId: Long) {
        router.pushController(
            ExtensionPreferencesController(sourceId).withFadeTransaction()
        )
    }

    private companion object {
        const val PKGNAME_KEY = "pkg_name"
    }
}
