package com.kalathil.intercom.tv

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kalathil.intercom.core.RemoteParticipant
import com.kalathil.intercom.core.VideoGrid
import com.kalathil.intercom.core.WebRtcEngine
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Everything grandpa ever sees.
 *
 * Deliberately almost wordless: a large name, a colour, and a picture. He is not
 * expected to read instructions, find a menu, or know which app he is in.
 *
 * ## The privacy rule
 *
 * An emergency check-in opens the camera without him doing anything. That is a
 * real capability and it is never silent: a chime plays, and a thick red border
 * is drawn around the entire screen, on top of everything else, for the whole
 * time the camera is transmitting. He can also kill the camera outright with a
 * held key at any moment. Being able to look into someone's living room
 * unannounced is not a feature this app has, and the border is the mechanism
 * that keeps it that way.
 */
class TvCallActivity : AppCompatActivity() {

    private lateinit var remoteGrid: VideoGrid
    private lateinit var localVideo: SurfaceViewRenderer
    private lateinit var statusPanel: View
    private lateinit var avatar: View
    private lateinit var avatarIcon: android.widget.ImageView
    private lateinit var headline: android.widget.TextView
    private lateinit var subhead: android.widget.TextView
    private lateinit var caption: android.widget.TextView
    private lateinit var liveBorder: View
    private lateinit var liveLabel: android.widget.TextView

    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null
    private var renderersInitialised = false
    private var boundEngine: WebRtcEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_call)

        remoteGrid = findViewById(R.id.remoteGrid)
        // Names under each picture, big enough to read from across the room.
        remoteGrid.labelTextSizeSp = 26f
        localVideo = findViewById(R.id.localVideo)
        statusPanel = findViewById(R.id.statusPanel)
        avatar = findViewById(R.id.avatar)
        avatarIcon = findViewById(R.id.avatarIcon)
        headline = findViewById(R.id.headline)
        subhead = findViewById(R.id.subhead)
        caption = findViewById(R.id.caption)
        liveBorder = findViewById(R.id.liveBorder)
        liveLabel = findViewById(R.id.liveLabel)

        toneGenerator = runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME)
        }.getOrNull()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    IntercomService.callState,
                    IntercomService.participants,
                    IntercomService.localEngine,
                    IntercomService.cameraStatus
                ) { state, people, engine, camera ->
                    Quad(state, people, engine, camera)
                }.collect { (state, people, engine, camera) ->
                    bindEngine(engine)
                    remoteGrid.show(people)
                    render(state, camera)
                }
            }
        }
    }

    /**
     * Remote presses arrive here too when our activity happens to be in front.
     * The accessibility service handles the general case; this covers the window
     * where it is our own activity that has focus.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bindings = KeyBindings(this)
        when (keyCode) {
            // Call also answers when ringing, and calls again once a call has ended.
            bindings.callKey -> {
                IntercomService.send(this, IntercomService.ACTION_PRESS_CALL)
                return true
            }
            bindings.answerKey -> {
                IntercomService.send(this, IntercomService.ACTION_PRESS_ANSWER)
                return true
            }
            bindings.hangupKey -> {
                IntercomService.send(this, IntercomService.ACTION_PRESS_HANGUP)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -----------------------------------------------------------------------

    private fun bindEngine(engine: WebRtcEngine?) {
        if (engine === boundEngine) return

        // Renderers must be initialised against the engine's EGL context, and
        // that context dies with the engine, so they are torn down together.
        remoteGrid.attach(engine?.eglBase?.eglBaseContext)
        runCatching { boundEngine?.unbindLocalPreview(localVideo) }
        if (renderersInitialised) {
            runCatching { localVideo.release() }
            renderersInitialised = false
        }

        boundEngine = engine
        if (engine == null) return

        runCatching {
            localVideo.init(engine.eglBase.eglBaseContext, null)
            localVideo.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            localVideo.setZOrderMediaOverlay(true)
            localVideo.setEnableHardwareScaler(true)

            engine.bindLocalPreview(localVideo)
            renderersInitialised = true
        }.onFailure { Log.e(TAG, "could not set up video renderers", it) }
    }

    // -----------------------------------------------------------------------

    private fun render(state: CallUiState, cameraStatus: String) {
        // A new call during the few seconds an ending is on screen must not be
        // closed by that ending's timer.
        if (state !is CallUiState.Ended) handler.removeCallbacks(dismiss)
        when (state) {
            is CallUiState.Idle -> finish()

            is CallUiState.Placing, is CallUiState.Dialling -> {
                showPanel(
                    title = getString(R.string.calling_family),
                    detail = getString(R.string.press_red_to_end),
                    accent = R.color.ok_green
                )
                hideVideo()
                stopLiveIndicator()
            }

            is CallUiState.Ringing -> {
                showPanel(
                    title = getString(R.string.incoming_from, state.fromName),
                    detail = getString(R.string.press_green_to_answer),
                    accent = R.color.ok_green
                )
                hideVideo()
                startRingTone()
            }

            is CallUiState.Connecting -> {
                stopRingTone()
                showPanel(
                    title = if (state.autoAnswered) {
                        getString(R.string.auto_answered, state.peerName)
                    } else {
                        getString(R.string.connecting)
                    },
                    detail = cameraStatus.ifBlank { getString(R.string.connecting) },
                    accent = R.color.amber
                )
                if (state.autoAnswered) startLiveIndicator()
            }

            is CallUiState.InCall -> {
                stopRingTone()
                statusPanel.visibility = View.GONE
                remoteGrid.visibility = View.VISIBLE
                localVideo.visibility = if (state.cameraOn) View.VISIBLE else View.GONE

                caption.visibility = View.VISIBLE
                caption.text = if (state.cameraOn) {
                    getString(R.string.in_call_with, state.peerName)
                } else {
                    getString(R.string.camera_off) + " · " + state.peerName
                }

                // The border stays up for the whole of an auto-answered call,
                // and only while the camera is actually transmitting.
                if (state.autoAnswered && state.cameraOn) startLiveIndicator() else stopLiveIndicator()
            }

            is CallUiState.Ended -> {
                stopRingTone()
                stopLiveIndicator()
                hideVideo()
                showPanel(
                    title = when (state.reason) {
                        EndReason.NOBODY_ANSWERED -> getString(R.string.nobody_answered)
                        EndReason.MISSED -> getString(R.string.missed_call)
                        EndReason.ANSWERED_ELSEWHERE -> getString(R.string.answered_elsewhere)
                        EndReason.CONNECTION_LOST -> getString(R.string.connection_lost)
                        EndReason.NO_PHONES -> getString(R.string.no_phones)
                        EndReason.FAILED -> getString(R.string.call_failed)
                        EndReason.HUNG_UP -> getString(R.string.call_ended)
                    },
                    detail = state.peerName.orEmpty(),
                    // Grey for an ordinary goodbye, red when something went wrong.
                    accent = if (state.reason == EndReason.HUNG_UP) R.color.text_dim else R.color.live_red,
                    icon = R.drawable.ic_call_end
                )
                // Leave it up long enough to be read from a sofa, then get out
                // of the way and give him his programme back.
                handler.removeCallbacks(dismiss)
                handler.postDelayed(dismiss, ENDED_DWELL_MS)
            }
        }
    }

    private val dismiss = Runnable {
        if (IntercomService.callState.value is CallUiState.Ended) {
            IntercomService.dismissEndedState()
            finish()
        }
    }

    /** [icon]: a camera while a call is on its way, a hung-up handset once it is over. */
    private fun showPanel(title: String, detail: String, accent: Int, icon: Int = R.drawable.ic_videocam) {
        avatarIcon.setImageResource(icon)
        statusPanel.visibility = View.VISIBLE
        caption.visibility = View.GONE
        headline.text = title
        subhead.text = detail
        subhead.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
        avatar.backgroundTintList = getColorStateList(accent)
    }

    private fun hideVideo() {
        remoteGrid.visibility = View.GONE
        localVideo.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // Audible signals
    // -----------------------------------------------------------------------

    private var ringing = false

    private fun startRingTone() {
        if (ringing) return
        ringing = true
        handler.post(ringRunnable)
    }

    private val ringRunnable = object : Runnable {
        override fun run() {
            if (!ringing) return
            toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1_000)
            handler.postDelayed(this, 3_000)
        }
    }

    private fun stopRingTone() {
        ringing = false
        handler.removeCallbacks(ringRunnable)
        runCatching { toneGenerator?.stopTone() }
    }

    /**
     * Announce that the camera is live: one clear chime, then a border that
     * stays for the whole call. The chime plays once per call so it marks the
     * start rather than nagging.
     */
    private fun startLiveIndicator() {
        if (liveBorder.visibility == View.VISIBLE) return
        liveBorder.visibility = View.VISIBLE
        liveLabel.visibility = View.VISIBLE
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600) }
    }

    private fun stopLiveIndicator() {
        liveBorder.visibility = View.GONE
        liveLabel.visibility = View.GONE
    }

    // -----------------------------------------------------------------------

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRingTone()
        remoteGrid.clear()
        runCatching { boundEngine?.unbindLocalPreview(localVideo) }
        if (renderersInitialised) runCatching { localVideo.release() }
        runCatching { toneGenerator?.release() }
        toneGenerator = null
        super.onDestroy()
    }

    private data class Quad(
        val state: CallUiState,
        val people: List<RemoteParticipant>,
        val engine: WebRtcEngine?,
        val camera: String
    )

    private companion object {
        const val TAG = "TvCallActivity"
        const val TONE_VOLUME = 80
        const val ENDED_DWELL_MS = 6_000L
    }
}
