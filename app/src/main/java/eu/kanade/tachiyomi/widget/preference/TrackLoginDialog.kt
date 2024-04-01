package eu.kanade.tachiyomi.widget.preference

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.system.toast
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TrackLoginDialog(
    @StringRes titleRes: Int? = null,
    titleFormatArgs: Any? = null,
    @StringRes usernameLabelRes: Int? = null,
    bundle: Bundle? = null
) : LoginDialogPreference(titleRes, titleFormatArgs, usernameLabelRes, bundle) {
    private val service = Injekt.get<TrackManager>().getService(args.getInt("key"))!!

    constructor(service: TrackService) : this(service, null)

    constructor(
        service: TrackService,
        @StringRes usernameLabelRes: Int?
    ) :
        this(R.string.login_title, service.name, usernameLabelRes, Bundle().apply { putInt("key", service.id) })

    override fun setCredentialsOnView(view: View) {
        binding.username.setText(service.getUsername())
        binding.password.setText(service.getPassword())
    }

    override fun checkLogin() {
        requestSubscription?.unsubscribe()

        v?.apply {
            if (binding.username.text.isNullOrEmpty() || binding.password.text.isNullOrEmpty()) {
                return
            }

            binding.login.progress = 1
            val user = binding.username.text.toString()
            val pass = binding.password.text.toString()

            requestSubscription =
                service.login(user, pass)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        {
                            dialog?.dismiss()
                            context.toast(R.string.login_success)
                        },
                        { error ->
                            binding!!.login.progress = -1
                            binding!!.login.setText(R.string.unknown_error)
                            error.message?.let { context.toast(it) }
                        }
                    )
        }
    }

    override fun onDialogClosed() {
        super.onDialogClosed()
        (targetController as? Listener)?.trackLoginDialogClosed(service)
    }

    interface Listener {
        fun trackLoginDialogClosed(service: TrackService)
    }
}
