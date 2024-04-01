package exh.ui.batchadd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.afollestad.materialdialogs.MaterialDialog
import eu.kanade.tachiyomi.databinding.EhFragmentBatchAddBinding
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.util.lang.combineLatest
import eu.kanade.tachiyomi.util.lang.plusAssign
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.view.clicks
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription

/**
 * Batch add screen
 */
class BatchAddController : NucleusController<EhFragmentBatchAddBinding, BatchAddPresenter>() {
    override fun inflateView(
        inflater: LayoutInflater,
        container: ViewGroup
    ): View {
        binding = EhFragmentBatchAddBinding.inflate(inflater)
        return binding.root
    }

    override fun getTitle() = "Batch add"

    override fun createPresenter() = BatchAddPresenter()

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        with(view) {
            binding.btnAddGalleries.clicks()
                .onEach {
                    addGalleries(binding.galleriesBox.text.toString())
                }
                .launchIn(scope)

            binding.progressDismissBtn.clicks()
                .onEach {
                    presenter.currentlyAddingRelay.call(BatchAddPresenter.STATE_PROGRESS_TO_INPUT)
                }
                .launchIn(scope)

            val progressSubscriptions = CompositeSubscription()

            presenter.currentlyAddingRelay
                .onBackpressureBuffer()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeUntilDestroy {
                    progressSubscriptions.clear()
                    if (it == BatchAddPresenter.STATE_INPUT_TO_PROGRESS) {
                        showProgress(this)
                        progressSubscriptions +=
                            presenter.progressRelay
                                .onBackpressureBuffer()
                                .observeOn(AndroidSchedulers.mainThread())
                                .combineLatest(presenter.progressTotalRelay) { progress, total ->
                                    // Show hide dismiss button
                                    binding.progressDismissBtn.visibility =
                                        if (progress == total) {
                                            View.VISIBLE
                                        } else {
                                            View.GONE
                                        }

                                    formatProgress(progress, total)
                                }.subscribeUntilDestroy {
                                    binding.progressText.text = it
                                }

                        progressSubscriptions +=
                            presenter.progressTotalRelay
                                .onBackpressureBuffer()
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeUntilDestroy {
                                    binding.progressBar.max = it
                                }

                        progressSubscriptions +=
                            presenter.progressRelay
                                .onBackpressureBuffer()
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeUntilDestroy {
                                    binding.progressBar.progress = it
                                }

                        presenter.eventRelay
                            ?.onBackpressureBuffer()
                            ?.observeOn(AndroidSchedulers.mainThread())
                            ?.subscribeUntilDestroy {
                                binding.progressLog.append("$it\n")
                            }?.let {
                                progressSubscriptions += it
                            }
                    } else if (it == BatchAddPresenter.STATE_PROGRESS_TO_INPUT) {
                        hideProgress(this)
                        presenter.currentlyAddingRelay.call(BatchAddPresenter.STATE_IDLE)
                    }
                }
        }
    }

    private val View.progressViews
        get() =
            listOf(
                binding.progressTitleView,
                binding.progressLogWrapper,
                binding.progressBar,
                binding.progressText,
                binding.progressDismissBtn
            )

    private val View.inputViews
        get() =
            listOf(
                binding.inputTitleView,
                binding.galleriesBox,
                binding.btnAddGalleries
            )

    private val View.progressLog
        get() = binding.progressLog

    private val View.galleriesBox
        get() = binding.galleriesBox

    private var List<View>.visibility: Int
        get() = throw UnsupportedOperationException()
        set(v) {
            forEach { it.visibility = v }
        }

    private fun showProgress(target: View? = view) {
        target?.apply {
            progressViews.visibility = View.VISIBLE
            inputViews.visibility = View.GONE
        }?.progressLog?.text = ""
    }

    private fun hideProgress(target: View? = view) {
        target?.apply {
            progressViews.visibility = View.GONE
            inputViews.visibility = View.VISIBLE
        }?.galleriesBox?.setText("", TextView.BufferType.EDITABLE)
    }

    private fun formatProgress(
        progress: Int,
        total: Int
    ) = "$progress/$total"

    private fun addGalleries(galleries: String) {
        // Check text box has content
        if (galleries.isBlank()) {
            noGalleriesSpecified()
            return
        }

        presenter.addGalleries(galleries)
    }

    private fun noGalleriesSpecified() {
        activity?.let {
            MaterialDialog(it)
                .title(text = "No galleries to add!")
                .message(text = "You must specify at least one gallery to add!")
                .positiveButton(android.R.string.ok) { materialDialog -> materialDialog.dismiss() }
                .cancelable(true)
                .cancelOnTouchOutside(true)
                .show()
        }
    }
}
