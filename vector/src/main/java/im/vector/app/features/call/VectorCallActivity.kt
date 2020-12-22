/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.viewModel
import im.vector.app.R
import im.vector.app.core.di.ScreenComponent
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.services.CallService
import im.vector.app.core.utils.PERMISSIONS_FOR_AUDIO_IP_CALL
import im.vector.app.core.utils.PERMISSIONS_FOR_VIDEO_IP_CALL
import im.vector.app.core.utils.allGranted
import im.vector.app.core.utils.checkPermissions
import im.vector.app.databinding.ActivityCallBinding
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.detail.RoomDetailArgs
import io.reactivex.android.schedulers.AndroidSchedulers
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCallDetail
import org.matrix.android.sdk.api.session.call.MxPeerConnectionState
import org.matrix.android.sdk.api.session.call.TurnServerResponse
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class CallArgs(
        val roomId: String,
        val callId: String,
        val participantUserId: String,
        val isIncomingCall: Boolean,
        val isVideoCall: Boolean
) : Parcelable

class VectorCallActivity : VectorBaseActivity<ActivityCallBinding>(), CallControlsView.InteractionListener {

    override fun getBinding() = ActivityCallBinding.inflate(layoutInflater)

    @Inject lateinit var avatarRenderer: AvatarRenderer

    override fun injectWith(injector: ScreenComponent) {
        super.injectWith(injector)
        injector.inject(this)
    }

    private val callViewModel: VectorCallViewModel by viewModel()
    private lateinit var callArgs: CallArgs

    @Inject lateinit var callManager: WebRtcCallManager

    @Inject lateinit var viewModelFactory: VectorCallViewModel.Factory

    private var rootEglBase: EglBase? = null

    var surfaceRenderersAreInitialized = false

    override fun doBeforeSetContentView() {
        setContentView(R.layout.activity_call)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        super.onCreate(savedInstanceState)

        if (intent.hasExtra(MvRx.KEY_ARG)) {
            callArgs = intent.getParcelableExtra(MvRx.KEY_ARG)!!
        } else {
            Timber.e("## VOIP missing callArgs for VectorCall Activity")
            CallService.onNoActiveCall(this)
            finish()
        }

        Timber.v("## VOIP EXTRA_MODE is ${intent.getStringExtra(EXTRA_MODE)}")
        if (intent.getStringExtra(EXTRA_MODE) == INCOMING_RINGING) {
            turnScreenOnAndKeyguardOff()
        }

        configureCallViews()

        callViewModel.subscribe(this) {
            renderState(it)
        }

        callViewModel.viewEvents
                .observe()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    handleViewEvents(it)
                }
                .disposeOnDestroy()

        if (callArgs.isVideoCall) {
            if (checkPermissions(PERMISSIONS_FOR_VIDEO_IP_CALL, this, CAPTURE_PERMISSION_REQUEST_CODE, R.string.permissions_rationale_msg_camera_and_audio)) {
                start()
            }
        } else {
            if (checkPermissions(PERMISSIONS_FOR_AUDIO_IP_CALL, this, CAPTURE_PERMISSION_REQUEST_CODE, R.string.permissions_rationale_msg_record_audio)) {
                start()
            }
        }
    }

    override fun onDestroy() {
        callManager.getCallById(callArgs.callId)?.detachRenderers(listOf(views.pipRenderer, views.fullscreenRenderer))
        if (surfaceRenderersAreInitialized) {
            views.pipRenderer.release()
            views.fullscreenRenderer.release()
        }
        turnScreenOffAndKeyguardOn()
        super.onDestroy()
    }

    private fun renderState(state: VectorCallViewState) {
        Timber.v("## VOIP renderState call $state")
        if (state.callState is Fail) {
            // be sure to clear notification
            CallService.onNoActiveCall(this)
            finish()
            return
        }

        views.callControlsView.updateForState(state)
        val callState = state.callState.invoke()
        views.callConnectingProgress.isVisible = false
        views.callActionText.setOnClickListener(null)
        views.callActionText.isVisible = false
        views.smallIsHeldIcon.isVisible = false
        when (callState) {
            is CallState.Idle,
            is CallState.Dialing -> {
                views.callVideoGroup.isInvisible = true
                views.callInfoGroup.isVisible = true
                views.callStatusText.setText(R.string.call_ring)
                configureCallInfo(state)
            }

            is CallState.LocalRinging -> {
                views.callVideoGroup.isInvisible = true
                views.callInfoGroup.isVisible = true
                views.callStatusText.text = null
                configureCallInfo(state)
            }

            is CallState.Answering -> {
                views.callVideoGroup.isInvisible = true
                views.callInfoGroup.isVisible = true
                views.callStatusText.setText(R.string.call_connecting)
                views.callConnectingProgress.isVisible = true
                configureCallInfo(state)
            }
            is CallState.Connected -> {
                if (callState.iceConnectionState == MxPeerConnectionState.CONNECTED) {
                    if (state.isLocalOnHold) {
                        views.smallIsHeldIcon.isVisible = true
                        views.callVideoGroup.isInvisible = true
                        views.callInfoGroup.isVisible = true
                        configureCallInfo(state, blurAvatar = true)
                        if (state.isRemoteOnHold) {
                            views.callActionText.setText(R.string.call_resume_action)
                            views.callActionText.isVisible = true
                            views.callActionText.setOnClickListener { callViewModel.handle(VectorCallViewActions.ToggleHoldResume) }
                            views.callStatusText.setText(R.string.call_held_by_you)
                        } else {
                            views.callActionText.isInvisible = true
                            state.otherUserMatrixItem.invoke()?.let {
                                views.callStatusText.text = getString(R.string.call_held_by_user, it.getBestName())
                            }
                        }
                    } else {
                        views.callStatusText.text = null
                        if (callArgs.isVideoCall) {
                            views.callVideoGroup.isVisible = true
                            views.callInfoGroup.isVisible = false
                            //views.pip_video_view.isVisible = !state.isVideoCaptureInError
                        } else {
                            views.callVideoGroup.isInvisible = true
                            views.callInfoGroup.isVisible = true
                            configureCallInfo(state)
                        }
                    }
                } else {
                    // This state is not final, if you change network, new candidates will be sent
                    views.callVideoGroup.isInvisible = true
                    views.callInfoGroup.isVisible = true
                    configureCallInfo(state)
                    views.callStatusText.setText(R.string.call_connecting)
                    views.callConnectingProgress.isVisible = true
                }
                // ensure all attached?
                callManager.getCallById(callArgs.callId)?.attachViewRenderers(views.pipRenderer, views.fullscreenRenderer, null)
            }
            is CallState.Terminated -> {
                finish()
            }
            null -> {
            }
        }
    }

    private fun configureCallInfo(state: VectorCallViewState, blurAvatar: Boolean = false) {
        state.otherUserMatrixItem.invoke()?.let {
            val colorFilter = ContextCompat.getColor(this, R.color.bg_call_screen)
            avatarRenderer.renderBlur(it, views.bgCallView, sampling = 20, rounded = false, colorFilter = colorFilter)
            views.participantNameText.text = it.getBestName()
            if (blurAvatar) {
                avatarRenderer.renderBlur(it, views.otherMemberAvatar, sampling = 2, rounded = true, colorFilter = colorFilter)
            } else {
                avatarRenderer.render(it, views.otherMemberAvatar)
            }
        }
    }

    private fun configureCallViews() {
        views.callControlsView.interactionListener = this
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAPTURE_PERMISSION_REQUEST_CODE && allGranted(grantResults)) {
            start()
        } else {
            // TODO display something
            finish()
        }
    }

    private fun start() {
        rootEglBase = EglUtils.rootEglBase ?: return Unit.also {
            Timber.v("## VOIP rootEglBase is null")
            finish()
        }

        // Init Picture in Picture renderer
        views.pipRenderer.init(rootEglBase!!.eglBaseContext, null)
        views.pipRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        // Init Full Screen renderer
        views.fullscreenRenderer.init(rootEglBase!!.eglBaseContext, null)
        views.fullscreenRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        views.pipRenderer.setZOrderMediaOverlay(true)
        views.pipRenderer.setEnableHardwareScaler(true /* enabled */)
        views.fullscreenRenderer.setEnableHardwareScaler(true /* enabled */)

        callManager.getCallById(callArgs.callId)?.attachViewRenderers(views.pipRenderer, views.fullscreenRenderer,
                intent.getStringExtra(EXTRA_MODE)?.takeIf { isFirstCreation() })

        views.pipRenderer.setOnClickListener {
            callViewModel.handle(VectorCallViewActions.ToggleCamera)
        }
        surfaceRenderersAreInitialized = true
    }

    private fun handleViewEvents(event: VectorCallViewEvents?) {
        Timber.v("## VOIP handleViewEvents $event")
        when (event) {
            VectorCallViewEvents.DismissNoCall -> {
                CallService.onNoActiveCall(this)
                finish()
            }
            is VectorCallViewEvents.ConnectionTimeout -> {
                onErrorTimoutConnect(event.turn)
            }
            null -> {
            }
        }
    }

    private fun onErrorTimoutConnect(turn: TurnServerResponse?) {
        Timber.d("## VOIP onErrorTimoutConnect $turn")
        // TODO ask to use default stun, etc...
        AlertDialog
                .Builder(this)
                .setTitle(R.string.call_failed_no_connection)
                .setMessage(getString(R.string.call_failed_no_connection_description))
                .setNegativeButton(R.string.ok) { _, _ ->
                    callViewModel.handle(VectorCallViewActions.EndCall)
                }
                .show()
    }

    companion object {

        private const val CAPTURE_PERMISSION_REQUEST_CODE = 1
        private const val EXTRA_MODE = "EXTRA_MODE"

        const val OUTGOING_CREATED = "OUTGOING_CREATED"
        const val INCOMING_RINGING = "INCOMING_RINGING"
        const val INCOMING_ACCEPT = "INCOMING_ACCEPT"

        fun newIntent(context: Context, mxCall: MxCallDetail, mode: String?): Intent {
            return Intent(context, VectorCallActivity::class.java).apply {
                // what could be the best flags?
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MvRx.KEY_ARG, CallArgs(mxCall.roomId, mxCall.callId, mxCall.opponentUserId, !mxCall.isOutgoing, mxCall.isVideoCall))
                putExtra(EXTRA_MODE, mode)
            }
        }

        fun newIntent(context: Context,
                      callId: String,
                      roomId: String,
                      otherUserId: String,
                      isIncomingCall: Boolean,
                      isVideoCall: Boolean,
                      mode: String?): Intent {
            return Intent(context, VectorCallActivity::class.java).apply {
                // what could be the best flags?
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MvRx.KEY_ARG, CallArgs(roomId, callId, otherUserId, isIncomingCall, isVideoCall))
                putExtra(EXTRA_MODE, mode)
            }
        }
    }

    override fun didAcceptIncomingCall() {
        callViewModel.handle(VectorCallViewActions.AcceptCall)
    }

    override fun didDeclineIncomingCall() {
        callViewModel.handle(VectorCallViewActions.DeclineCall)
    }

    override fun didEndCall() {
        callViewModel.handle(VectorCallViewActions.EndCall)
    }

    override fun didTapToggleMute() {
        callViewModel.handle(VectorCallViewActions.ToggleMute)
    }

    override fun didTapToggleVideo() {
        callViewModel.handle(VectorCallViewActions.ToggleVideo)
    }

    override fun returnToChat() {
        val args = RoomDetailArgs(callArgs.roomId)
        val intent = RoomDetailActivity.newIntent(this, args).apply {
            flags = FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        // is it needed?
        finish()
    }

    override fun didTapMore() {
        CallControlsBottomSheet().show(supportFragmentManager, "Controls")
    }

    // Needed to let you answer call when phone is locked
    private fun turnScreenOnAndKeyguardOff() {
        Timber.v("## VOIP turnScreenOnAndKeyguardOff")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        with(getSystemService<KeyguardManager>()!!) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestDismissKeyguard(this@VectorCallActivity, null)
            }
        }
    }

    private fun turnScreenOffAndKeyguardOn() {
        Timber.v("## VOIP turnScreenOnAndKeyguardOn")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }
}
