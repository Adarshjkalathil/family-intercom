package com.kalathil.intercom.phone

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
 * The ringing and in-call screen, shown over the lock screen.
 */
class PhoneCallActivity : AppCompatActivity() {

    private lateinit var remoteGrid: VideoGrid
    private lateinit var localVideo: SurfaceViewRenderer
    private lateinit var statusPanel: View
    private lateinit var headline: TextView
    private lateinit var subhead: TextView
    private lateinit var ringControls: View
    private lateinit var inCallControls: View
    private lateinit var muteButton: MaterialButton

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null

    private var renderersInitialised = false
    private var boundEngine: WebRtcEngine? = null
    private var currentCallId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_phone_call)

        remoteGrid = findViewById(R.id.remoteGrid)
        localVideo = findViewById(R.id.localVideo)
        statusPanel = findViewById(R.id.statusPanel)
        headline = findViewById(R.id.headline)
        subhead = findViewById(R.id.subhead)
        ringControls = findViewById(R.id.controls)
        inCallControls = findViewById(R.id.inCallControls)
        muteButton = findViewById(R.id.mute)

        vibrator = getSystemService(Vibrator::class.java)

        findViewById<Button>(R.id.answer).setOnClickListener {
            currentCallId?.let {
                PhoneIntercomService.send(this, PhoneIntercomService.ACTION_ACCEPT, it)
            }
        }
        findViewById<Button>(R.id.decline).setOnClickListener {
            currentCallId?.let {
                PhoneIntercomService.send(this, PhoneIntercomService.ACTION_DECLINE, it)
            }
        }
        findViewById<Button>(R.id.hangUp).setOnClickListener {
            PhoneIntercomService.send(this, PhoneIntercomService.ACTION_HANGUP)
        }
        findViewById<Button>(R.id.switchCamera).setOnClickListener {
            PhoneIntercomService.send(this, PhoneIntercomService.ACTION_SWITCH_CAMERA)
        }
        muteButton.setOnClickListener {
            PhoneIntercomService.send(this, PhoneIntercomService.ACTION_TOGGLE_MUTE)
        }
        // Tapping the video switches between whole pictures and filling the screen.
        remoteGrid.setOnClickListener { remoteGrid.fill = !remoteGrid.fill }
        sizeLocalPreview()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Mirrored like a mirror for the front camera, true for the back.
                    PhoneIntercomService.frontCamera.collect { localVideo.setMirror(it) }
                }
                combine(
                    PhoneIntercomService.callState,
                    PhoneIntercomService.participants,
                    PhoneIntercomService.localEngine,
                    PhoneIntercomService.muted
                ) { state, people, engine, muted -> Quad(state, people, engine, muted) }
                    .collect { (state, people, engine, muted) ->
                        bindEngine(engine)
                        remoteGrid.show(people)
                        muteButton.setIconResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
                        muteButton.contentDescription = getString(if (muted) R.string.unmute else R.string.mute)
                        render(state)
                    }
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun bindEngine(engine: WebRtcEngine?) {
        if (engine === boundEngine) return

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
            localVideo.setMirror(PhoneIntercomService.frontCamera.value)
            engine.bindLocalPreview(localVideo)
            renderersInitialised = true
        }.onFailure { Log.e(TAG, "renderer setup failed", it) }
    }

    /**
     * The camera tags each frame with the screen's rotation, not the phone's. With
     * auto-rotate off, a phone turned sideways still reports upright, so the TV
     * showed the picture on its side. Following the sensor during a call keeps
     * the screen, and so the picture, the right way up.
     */
    private fun followSensor(inCall: Boolean) {
        val wanted = if (inCall) ActivityInfo.SCREEN_ORIENTATION_SENSOR
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** Our own picture is upright in portrait and wide in landscape. */
    private fun sizeLocalPreview() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val long = (160 * resources.displayMetrics.density).toInt()
        val short = (120 * resources.displayMetrics.density).toInt()
        localVideo.layoutParams = localVideo.layoutParams.apply {
            width = if (landscape) long else short
            height = if (landscape) short else long
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeLocalPreview()
    }

    private fun render(state: PhoneCallState) {
        // A new call during the moment an ending is on screen must not be
        // closed by that ending's timer.
        if (state !is PhoneCallState.Ended) handler.removeCallbacks(dismiss)
        followSensor(state is PhoneCallState.InCall)
        when (state) {
            is PhoneCallState.Idle -> finish()

            is PhoneCallState.Ringing -> {
                currentCallId = state.callId
                statusPanel.visibility = View.VISIBLE
                ringControls.visibility = View.VISIBLE
                inCallControls.visibility = View.GONE
                remoteGrid.visibility = View.GONE
                localVideo.visibility = View.GONE
                headline.text = getString(R.string.incoming_from, state.fromName)
                subhead.text = ""
                startRinging()
            }

            is PhoneCallState.Placing, is PhoneCallState.Dialling -> {
                currentCallId = (state as? PhoneCallState.Dialling)?.callId
                stopRinging()
                statusPanel.visibility = View.VISIBLE
                ringControls.visibility = View.GONE
                inCallControls.visibility = View.VISIBLE
                remoteGrid.visibility = View.GONE
                localVideo.visibility = View.GONE
                headline.text = getString(R.string.calling)
                subhead.text = ""
            }

            is PhoneCallState.Connecting -> {
                currentCallId = state.callId
                stopRinging()
                statusPanel.visibility = View.VISIBLE
                ringControls.visibility = View.GONE
                inCallControls.visibility = View.VISIBLE
                remoteGrid.visibility = View.GONE
                localVideo.visibility = View.GONE
                headline.text = getString(R.string.connecting)
                subhead.text = state.peerName
            }

            is PhoneCallState.InCall -> {
                currentCallId = state.callId
                stopRinging()
                statusPanel.visibility = View.GONE
                ringControls.visibility = View.GONE
                inCallControls.visibility = View.VISIBLE
                remoteGrid.visibility = View.VISIBLE
                localVideo.visibility = View.VISIBLE
            }

            is PhoneCallState.Ended -> {
                stopRinging()
                currentCallId = null
                statusPanel.visibility = View.VISIBLE
                ringControls.visibility = View.GONE
                inCallControls.visibility = View.GONE
                remoteGrid.visibility = View.GONE
                localVideo.visibility = View.GONE
                headline.text = state.reason
                subhead.text = ""
                handler.removeCallbacks(dismiss)
                handler.postDelayed(dismiss, 2_500)
            }
        }
    }

    private val dismiss = Runnable {
        if (PhoneIntercomService.callState.value is PhoneCallState.Ended) {
            PhoneIntercomService.clearEnded()
            finish()
        }
    }

    // -----------------------------------------------------------------------

    private fun startRinging() {
        if (ringtone?.isPlaying == true) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                @Suppress("DEPRECATION")
                streamType = AudioManager.STREAM_RING
                play()
            }
        }.onFailure { Log.w(TAG, "could not play a ringtone", it) }

        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0)
            )
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopRinging()
        remoteGrid.clear()
        runCatching { boundEngine?.unbindLocalPreview(localVideo) }
        if (renderersInitialised) runCatching { localVideo.release() }
        super.onDestroy()
    }

    private data class Quad(
        val state: PhoneCallState,
        val people: List<RemoteParticipant>,
        val engine: WebRtcEngine?,
        val muted: Boolean
    )

    private companion object { const val TAG = "PhoneCallActivity" }
}
