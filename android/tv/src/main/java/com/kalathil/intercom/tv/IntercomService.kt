package com.kalathil.intercom.tv

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kalathil.intercom.core.AudioModuleFactory
import com.kalathil.intercom.core.CallMode
import com.kalathil.intercom.core.Config
import com.kalathil.intercom.core.RemoteParticipant
import com.kalathil.intercom.core.ServerEvent
import com.kalathil.intercom.core.SignallingClient
import com.kalathil.intercom.core.WebRtcEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

/**
 * The always-on half of the TV app.
 *
 * Holds the signalling socket open, owns the WebRTC engine, and turns button
 * presses into calls. It runs as a foreground service because the alternative -
 * relying on an activity staying alive - means the call button silently stops
 * working the first time Android reclaims memory while grandpa is watching
 * something else, and nobody finds out until it matters.
 */
class IntercomService : LifecycleService() {

    private lateinit var config: Config
    private lateinit var signalling: SignallingClient
    private var engine: WebRtcEngine? = null
    private var capturer: UvcCameraCapturer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Set when we answered without being asked, so the UI can announce it. */
    private var currentCallAutoAnswered = false
    private var placingTimeout: Job? = null

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        signalling = SignallingClient(config, role = "tv", scope = lifecycleScope)

        createNotificationChannel()
        startForegroundWithType()

        lifecycleScope.launch {
            signalling.events.collect { handleEvent(it) }
        }
        lifecycleScope.launch {
            signalling.state.collect { state ->
                _connectionState.value = state
                updateNotification()
            }
        }

        if (config.isConfigured) {
            signalling.connect()
        } else {
            Log.w(TAG, "not configured yet - open the app on the TV and fill in Setup")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PRESS_CALL -> onCallButton()
            ACTION_PRESS_ANSWER -> onAnswerButton()
            ACTION_PRESS_HANGUP -> onHangupButton()
            ACTION_TOGGLE_CAMERA -> onCameraToggle()
            ACTION_RECONNECT -> {
                // Setup sends this after permissions change, so the service can
                // claim microphone/camera use it now has.
                startForegroundWithType()
                signalling.connect()
            }
            ACTION_SETTINGS_CHANGED -> {
                // connect() alone would keep using the old address and secret.
                startForegroundWithType()
                signalling.reconnect()
            }
        }
        // The service must come back if the system kills it, or the button dies.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // -----------------------------------------------------------------------
    // Buttons
    // -----------------------------------------------------------------------

    private fun onCallButton() {
        when (val state = _callState.value) {
            is CallUiState.Ringing -> acceptCall(state) // pressing call also answers
            is CallUiState.Idle, is CallUiState.Ended -> {
                if (signalling.state.value != SignallingClient.State.CONNECTED ||
                    !signalling.requestCall(CallMode.NORMAL)
                ) {
                    Log.w(TAG, "call button pressed while offline")
                    _callState.value = CallUiState.Ended(EndReason.CONNECTION_LOST)
                    bringCallScreenToFront()
                    signalling.connect()
                    return
                }
                Log.i(TAG, "placing a call to everyone")
                // Not Idle, or the call screen would close itself before the
                // server has even answered.
                _callState.value = CallUiState.Placing
                showCallScreen()
                placingTimeout?.cancel()
                placingTimeout = lifecycleScope.launch {
                    delay(PLACING_TIMEOUT_MS)
                    if (_callState.value is CallUiState.Placing) {
                        Log.w(TAG, "server never confirmed the call")
                        finishCall(EndReason.FAILED)
                    }
                }
            }
            else -> Log.d(TAG, "call button ignored in state $state")
        }
    }

    private fun onAnswerButton() {
        val state = _callState.value
        if (state is CallUiState.Ringing) acceptCall(state)
    }

    private fun onHangupButton() {
        when (val state = _callState.value) {
            // The server has not confirmed yet; CallStarted cancels it if it does.
            is CallUiState.Placing -> finishCall(EndReason.HUNG_UP)
            is CallUiState.Ringing -> {
                // Reject only; the server ends the call once every target has.
                signalling.rejectCall(state.callId)
                finishCall(EndReason.HUNG_UP)
            }
            is CallUiState.Dialling -> {
                signalling.hangup(state.callId, "cancelled")
                finishCall(EndReason.HUNG_UP)
            }
            is CallUiState.Connecting -> {
                signalling.hangup(state.callId, "hangup")
                finishCall(EndReason.HUNG_UP)
            }
            is CallUiState.InCall -> {
                signalling.hangup(state.callId, "hangup")
                finishCall(EndReason.HUNG_UP)
            }
            else -> Unit
        }
    }

    /**
     * Grandpa's camera switch. Takes effect immediately, mid-call included:
     * the far end sees the picture stop.
     */
    private fun onCameraToggle() {
        val enabled = !config.cameraEnabled
        config.cameraEnabled = enabled
        Log.i(TAG, "camera switched ${if (enabled) "on" else "off"} by the physical key")
        engine?.setCameraEnabled(enabled)

        val message = getString(if (enabled) R.string.camera_switched_on else R.string.camera_switched_off)
        _cameraStatus.value = message
        refreshCameraOn()
        // Mid-call the screen itself shows it. Otherwise he still needs to see
        // that the press did something.
        if (currentCallId() == null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Whether the camera is really sending pictures right now. The red border
     * follows this, so it must be live rather than decided once when the call
     * connects: a webcam that finishes opening a few seconds later is
     * transmitting too.
     */
    private fun cameraTransmitting(): Boolean {
        val camera = capturer ?: return false
        return config.cameraEnabled && camera.lastError == null
    }

    private fun refreshCameraOn() {
        val state = _callState.value
        if (state is CallUiState.InCall) _callState.value = state.copy(cameraOn = cameraTransmitting())
    }

    private fun acceptCall(state: CallUiState.Ringing) {
        signalling.acceptCall(state.callId)
        _callState.value = CallUiState.Connecting(
            callId = state.callId,
            peerName = state.fromName,
            autoAnswered = false,
            fullScreenRemote = state.fullScreenRemote
        )
    }

    // -----------------------------------------------------------------------
    // Server events
    // -----------------------------------------------------------------------

    /** The call this TV is part of right now, or null between calls. */
    private fun currentCallId(): String? = when (val s = _callState.value) {
        is CallUiState.Dialling -> s.callId
        is CallUiState.Ringing -> s.callId
        is CallUiState.Connecting -> s.callId
        is CallUiState.InCall -> s.callId
        else -> null
    }

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.CallStarted -> {
                placingTimeout?.cancel()
                if (_callState.value !is CallUiState.Placing) {
                    // Hung up before the server confirmed: cancel it there too.
                    signalling.hangup(event.callId, "cancelled")
                    return
                }
                _callState.value = CallUiState.Dialling(event.callId, event.ringTimeoutMs)
                showCallScreen()
            }

            is ServerEvent.IncomingCall -> {
                val busyWith = currentCallId()
                if (busyWith != null && busyWith != event.callId) {
                    Log.w(TAG, "ignoring a second incoming call while in $busyWith")
                    return
                }
                // Already answered: a re-sent invite must not start it ringing again.
                if (busyWith == event.callId && _callState.value !is CallUiState.Ringing) return

                Log.i(TAG, "incoming call from ${event.fromName}, mode=${event.mode}")
                acquireWakeLock()

                if (event.mode == CallMode.EMERGENCY) {
                    // Auto-answer. Announced loudly by the UI - never silent.
                    currentCallAutoAnswered = true
                    signalling.acceptCall(event.callId)
                    _callState.value = CallUiState.Connecting(
                        callId = event.callId,
                        peerName = event.fromName,
                        autoAnswered = true,
                        fullScreenRemote = event.fullScreenRemote
                    )
                } else {
                    currentCallAutoAnswered = false
                    _callState.value = CallUiState.Ringing(
                        callId = event.callId,
                        fromName = event.fromName,
                        mode = event.mode,
                        fullScreenRemote = event.fullScreenRemote
                    )
                }
                showCallScreen()
            }

            // Somebody answered, joined or left: connect to exactly the people
            // the server says are in the call.
            is ServerEvent.CallPeers -> {
                if (event.callId != currentCallId()) return
                val names = event.peers.joinToString(", ") { it.peerName }
                when (val state = _callState.value) {
                    is CallUiState.InCall -> _callState.value = state.copy(peerName = names)
                    is CallUiState.Connecting -> _callState.value = state.copy(peerName = names)
                    else -> _callState.value = CallUiState.Connecting(
                        callId = event.callId,
                        peerName = names,
                        autoAnswered = currentCallAutoAnswered,
                        fullScreenRemote = event.fullScreenRemote
                    )
                }
                if (engine == null && !startMedia(event.callId)) return

                val known = _participants.value.associateBy { it.peerId }
                _participants.value = event.peers.map { peer ->
                    known[peer.peerId]?.copy(name = peer.peerName)
                        ?: RemoteParticipant(peer.peerId, peer.peerName, peer.role)
                }
                engine?.syncPeers(event.peers)
            }

            is ServerEvent.Signal ->
                if (event.callId == currentCallId()) engine?.handleSignal(event.from, event.payload)

            // Endings are matched on callId: a late one for a call that is
            // already over must not end the next call.
            is ServerEvent.CallTimeout ->
                if (event.callId == currentCallId()) finishCall(EndReason.NOBODY_ANSWERED)

            is ServerEvent.CallCancelled -> if (event.callId == currentCallId()) {
                val wasRinging = _callState.value is CallUiState.Ringing
                finishCall(
                    when (event.reason) {
                        // Somebody called him and it rang out: a missed call,
                        // with their name underneath. Otherwise he was calling,
                        // and every phone declining is, to him, nobody coming.
                        "timeout" -> if (wasRinging) EndReason.MISSED else EndReason.NOBODY_ANSWERED
                        "declined" -> EndReason.NOBODY_ANSWERED
                        "answered_elsewhere" -> EndReason.ANSWERED_ELSEWHERE
                        "peer_disconnected" -> EndReason.CONNECTION_LOST
                        else -> EndReason.HUNG_UP
                    }
                )
            }

            is ServerEvent.Error -> {
                Log.w(TAG, "server said: ${event.code} ${event.message}")
                val placing = _callState.value is CallUiState.Placing
                when {
                    event.code == "no_targets" && placing -> finishCall(EndReason.NO_PHONES)
                    event.code == "busy" && placing -> finishCall(EndReason.FAILED)
                    event.code == "unauthorised" && (placing || currentCallId() != null) ->
                        finishCall(EndReason.FAILED)
                }
            }

            // The server forgets a ringing or unanswered call whose device has
            // dropped, so waiting on one would hang forever. A call that is
            // already up carries on peer to peer; WebRTC reports if it breaks.
            // If the call is still live, the server re-sends it on reconnect.
            is ServerEvent.Disconnected -> when (_callState.value) {
                is CallUiState.Placing,
                is CallUiState.Dialling,
                is CallUiState.Ringing,
                is CallUiState.Connecting -> finishCall(EndReason.CONNECTION_LOST)
                else -> Unit
            }

            else -> Unit
        }
    }

    // -----------------------------------------------------------------------
    // Media
    // -----------------------------------------------------------------------

    /** Open the camera and microphone for a call. False if the call cannot go ahead. */
    private fun startMedia(callId: String): Boolean {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            // The call still connects, but WebRTC records silence: say so plainly.
            Log.e(TAG, "microphone permission not granted - open Family Intercom on the TV to allow it")
            _lastError.value = "Microphone permission is off. Open Family Intercom on the TV and allow it."
        }
        // A duplicate accept must not leave a second engine holding the camera.
        releaseEngine()

        // Claim microphone/camera use in case permission arrived after the service started.
        startForegroundWithType()
        AudioModuleFactory.enterCallMode(this, speakerphone = true)

        val webrtc = WebRtcEngine(
            context = this,
            signalling = signalling,
            // The webcam mic and the TV speakers are separate hardware, so the
            // platform's hardware echo canceller is the wrong tool - see
            // AudioModuleFactory for why software AEC wins here.
            preferSoftwareEchoCancellation = true
        )
        engine = webrtc

        webrtc.listener = object : WebRtcEngine.Listener {
            override fun onPeerStateChanged(peerId: String, state: PeerConnection.PeerConnectionState) {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        updateParticipant(peerId) { it.copy(connected = true) }
                        val current = _callState.value as? CallUiState.Connecting ?: return
                        _callState.value = CallUiState.InCall(
                            callId = callId,
                            peerName = current.peerName,
                            autoAnswered = currentCallAutoAnswered,
                            fullScreenRemote = current.fullScreenRemote,
                            cameraOn = cameraTransmitting()
                        )
                    }

                    // One person's connection failing takes only them out of
                    // the picture. With nobody left, the call is over.
                    PeerConnection.PeerConnectionState.FAILED -> {
                        Log.w(TAG, "lost the connection to ${peerId.take(8)}")
                        _participants.value = _participants.value.filter { it.peerId != peerId }
                        engine?.removePeer(peerId)
                        if (_participants.value.isEmpty()) {
                            currentCallId()?.let { signalling.hangup(it, "failed") }
                            finishCall(EndReason.CONNECTION_LOST)
                        }
                    }

                    else -> Unit
                }
            }

            override fun onRemoteVideoTrack(peerId: String, track: VideoTrack) {
                updateParticipant(peerId) { it.copy(track = track) }
            }

            override fun onError(message: String) {
                Log.e(TAG, "webrtc error: $message")
                _lastError.value = message
            }
        }

        // Camera off means audio only - the call still works, he just isn't seen.
        val videoCapturer = if (config.cameraEnabled) {
            UvcCameraCapturer(this) { status ->
                Log.i(TAG, "camera: $status")
                _cameraStatus.value = status
                refreshCameraOn()
            }.also { capturer = it }
        } else {
            _cameraStatus.value = getString(R.string.camera_switched_off)
            null
        }

        if (!webrtc.start(callId, videoCapturer)) {
            signalling.hangup(callId, "failed")
            finishCall(EndReason.FAILED)
            return false
        }
        _localEngine.value = webrtc
        return true
    }

    private fun updateParticipant(peerId: String, change: (RemoteParticipant) -> RemoteParticipant) {
        _participants.value = _participants.value.map { if (it.peerId == peerId) change(it) else it }
    }

    /** Stop the media. The UI is told first, so it lets go of the video before it is freed. */
    private fun releaseEngine() {
        _participants.value = emptyList()
        _localEngine.value = null
        engine?.release()
        engine = null
        capturer = null
    }

    private fun finishCall(reason: EndReason) {
        val peerName = when (val s = _callState.value) {
            is CallUiState.InCall -> s.peerName
            is CallUiState.Connecting -> s.peerName
            is CallUiState.Ringing -> s.fromName
            else -> null
        }

        placingTimeout?.cancel()
        releaseEngine()
        currentCallAutoAnswered = false

        AudioModuleFactory.leaveCallMode(this)
        releaseWakeLock()
        clearCallNotification()

        // No new notification for an ending: it would sit in the TV's list
        // saying "call in progress" long after the call. An open call screen
        // shows the ending by itself.
        _callState.value = CallUiState.Ended(reason, peerName)
    }

    // -----------------------------------------------------------------------

    /**
     * Bring the call screen to the front.
     *
     * Since Android 10 a background app cannot simply call startActivity, and a
     * foreground service is not an exemption. Two mechanisms are used together:
     *
     *  1. A full-screen-intent notification. This is the sanctioned path - it is
     *     what phone dialers use - and it works from the background.
     *  2. A direct startActivity, which succeeds when the press arrived through
     *     the accessibility service (those are exempt) or when the app already
     *     has a visible window.
     *
     * If both are blocked the notification still appears, so a call is never
     * lost silently. docs/05-troubleshooting.md covers the appops grant that
     * makes case 2 reliable on a TV with no "display over other apps" screen.
     */
    private fun showCallScreen() {
        val fullScreen = PendingIntent.getActivity(
            this, 1, callScreenIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, CHANNEL_CALL_ID)
            .setContentTitle(getString(R.string.notification_incoming_title))
            .setContentText(getString(R.string.notification_incoming_text))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setFullScreenIntent(fullScreen, true)
            // Selecting it from the notification list goes back to the call.
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, notification)
        bringCallScreenToFront()
    }

    /** Case 2 on its own: no notification, for when a press is answered at once. */
    private fun bringCallScreenToFront() {
        runCatching { startActivity(callScreenIntent()) }
            .onFailure { Log.w(TAG, "direct activity start blocked, relying on the full-screen intent", it) }
    }

    private fun callScreenIntent() = Intent(this, TvCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun clearCallNotification() {
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }

    /**
     * Wake the panel. On a TV where the panel and the Android device are the same
     * box, taking a screen wake lock is enough - there is no HDMI-CEC dance to do.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "intercom:incoming-call"
        ).apply { acquire(60_000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // Quiet, permanent: the "this thing is alive" notification.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Intercom status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the call button working"
                setShowBadge(false)
            }
        )

        // Loud: must be IMPORTANCE_HIGH or the full-screen intent is ignored and
        // the call screen never appears over whatever is playing.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming and outgoing calls"
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val text = when (signalling.state.value) {
            SignallingClient.State.CONNECTED -> "Ready. Press the button to call."
            SignallingClient.State.CONNECTING -> "Connecting…"
            SignallingClient.State.WAITING_TO_RETRY -> "Offline - retrying"
            SignallingClient.State.IDLE -> "Not connected"
            SignallingClient.State.UNAUTHORISED -> "Wrong home secret - check Settings"
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * Android 14 refuses to start a foreground service that claims a type it has
     * no permission for, and this service starts at boot, before anyone has
     * allowed the microphone. So claim only what is granted right now; Setup asks
     * for the rest and then sends ACTION_RECONNECT, which calls this again.
     */
    private fun startForegroundWithType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // connectedDevice is covered by CHANGE_NETWORK_STATE, which is always granted.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (hasPermission(Manifest.permission.CAMERA)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        Log.w(TAG, "intercom service destroyed")
        placingTimeout?.cancel()
        releaseEngine()
        signalling.disconnect()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL_ID = "intercom_status"
        private const val CHANNEL_CALL_ID = "intercom_calls"
        private const val NOTIFICATION_ID = 1
        private const val CALL_NOTIFICATION_ID = 2

        /** The server answers a call request in well under a second. */
        private const val PLACING_TIMEOUT_MS = 10_000L

        const val ACTION_PRESS_CALL = "com.kalathil.intercom.CALL"
        const val ACTION_PRESS_ANSWER = "com.kalathil.intercom.ANSWER"
        const val ACTION_PRESS_HANGUP = "com.kalathil.intercom.HANGUP"
        const val ACTION_TOGGLE_CAMERA = "com.kalathil.intercom.TOGGLE_CAMERA"
        const val ACTION_RECONNECT = "com.kalathil.intercom.RECONNECT"
        const val ACTION_SETTINGS_CHANGED = "com.kalathil.intercom.SETTINGS_CHANGED"

        private val _callState = MutableStateFlow<CallUiState>(CallUiState.Idle)
        val callState: StateFlow<CallUiState> = _callState.asStateFlow()

        private val _connectionState = MutableStateFlow(SignallingClient.State.IDLE)
        val connectionState: StateFlow<SignallingClient.State> = _connectionState.asStateFlow()

        /** Everyone else in the call, with their pictures as they arrive. */
        private val _participants = MutableStateFlow<List<RemoteParticipant>>(emptyList())
        val participants: StateFlow<List<RemoteParticipant>> = _participants.asStateFlow()

        private val _localEngine = MutableStateFlow<WebRtcEngine?>(null)
        val localEngine: StateFlow<WebRtcEngine?> = _localEngine.asStateFlow()

        private val _cameraStatus = MutableStateFlow("")
        val cameraStatus: StateFlow<String> = _cameraStatus.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, IntercomService::class.java)
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: String) {
            context.startForegroundService(
                Intent(context, IntercomService::class.java).setAction(action)
            )
        }

        /** Return to Idle once the "ended" message has been on screen long enough to read. */
        fun dismissEndedState() {
            if (_callState.value is CallUiState.Ended) _callState.value = CallUiState.Idle
        }
    }
}
