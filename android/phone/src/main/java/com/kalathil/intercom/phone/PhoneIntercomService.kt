package com.kalathil.intercom.phone

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
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kalathil.intercom.core.AudioModuleFactory
import com.kalathil.intercom.core.CallMode
import com.kalathil.intercom.core.CallSummary
import com.kalathil.intercom.core.Config
import com.kalathil.intercom.core.DevicePresence
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
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.PeerConnection
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack

/**
 * The phone's call engine.
 *
 * Mirrors the TV's IntercomService, with two differences: the camera comes from
 * Camera2 rather than USB, and the socket is opened on demand instead of being
 * held open forever. A phone that kept a socket alive all day would be killed by
 * battery optimisation anyway - that is what the push notification is for.
 */
class PhoneIntercomService : LifecycleService() {

    private lateinit var config: Config
    private lateinit var signalling: SignallingClient
    private var engine: WebRtcEngine? = null
    private var capturer: VideoCapturer? = null

    /** What we ask the camera for; see [fullViewSize]. */
    private var captureSize = 1280 to 720

    /** Set when a push arrived before the socket was up, so we can answer once connected. */
    private var pendingAcceptCallId: String? = null

    /** A call asked for before the socket was up, sent as soon as it is. */
    private var pendingCall: PendingCall? = null

    /** Gives up on a call the server never confirmed, or a ring nobody cancelled. */
    private var stateTimeout: Job? = null

    private data class PendingCall(val mode: CallMode, val fullScreenRemote: Boolean)

    override fun onCreate() {
        super.onCreate()
        config = Config(this)
        signalling = SignallingClient(config, role = "phone", scope = lifecycleScope)

        createChannels()
        startForegroundWithType("Connecting…")

        lifecycleScope.launch { signalling.events.collect { handleEvent(it) } }
        lifecycleScope.launch {
            signalling.state.collect {
                _connectionState.value = it
                if (it == SignallingClient.State.CONNECTED) onConnected()
            }
        }

        if (config.isConfigured) signalling.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT -> {
                signalling.connect()
                // A push token that arrives after the socket has registered (the
                // first launch with Firebase) would otherwise never reach the server.
                config.fcmToken?.let { token ->
                    if (signalling.state.value == SignallingClient.State.CONNECTED) {
                        signalling.updateFcmToken(token)
                    }
                }
            }

            // connect() alone would keep using the old address, secret and name.
            ACTION_SETTINGS_CHANGED -> signalling.reconnect()

            ACTION_CALL_TV -> placeCall(CallMode.NORMAL, fullScreenRemote = false)

            // "Put me on the TV": the TV auto-answers and shows our camera large.
            ACTION_SHOW_ME_ON_TV -> placeCall(CallMode.EMERGENCY, fullScreenRemote = true)

            ACTION_CHECK_IN -> placeCall(CallMode.EMERGENCY, fullScreenRemote = false)

            ACTION_PUSH_RING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (callId != null) {
                    ring(
                        callId = callId,
                        fromName = intent.getStringExtra(EXTRA_FROM_NAME) ?: "TV",
                        mode = CallMode.from(intent.getStringExtra(EXTRA_MODE))
                    )
                    signalling.connect()
                }
            }

            ACTION_ACCEPT -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (callId != null) acceptCall(callId)
            }

            ACTION_DECLINE -> {
                // Reject only. A hangup would end the call for the TV and for
                // every other phone still ringing.
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (callId != null && callId == currentCallId()) {
                    signalling.rejectCall(callId)
                    finishCall(getString(R.string.ended_declined))
                }
            }

            // The server has already cancelled this call, so there is nothing to
            // tell it - just silence a phone that was woken by push.
            ACTION_REMOTE_CANCELLED -> finishIfCurrent(
                intent.getStringExtra(EXTRA_CALL_ID),
                cancelReasonText(intent.getStringExtra(EXTRA_REASON))
            )

            ACTION_HANGUP -> {
                currentCallId()?.let { signalling.hangup(it, "hangup") }
                finishCall(getString(R.string.ended_call))
            }

            ACTION_SWITCH_CAMERA -> switchCamera()

            ACTION_TOGGLE_MUTE -> {
                _muted.value = !_muted.value
                engine?.setMicrophoneEnabled(!_muted.value)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // -----------------------------------------------------------------------

    private fun currentCallId(): String? = when (val s = _callState.value) {
        is PhoneCallState.Ringing -> s.callId
        is PhoneCallState.Dialling -> s.callId
        is PhoneCallState.Connecting -> s.callId
        is PhoneCallState.InCall -> s.callId
        else -> null
    }

    /** True from the moment a call is asked for until it ends. */
    private fun busy() = currentCallId() != null || _callState.value is PhoneCallState.Placing

    /** Ignore endings for a call we are not in, such as a cancel push arriving late. */
    private fun finishIfCurrent(callId: String?, reason: String) {
        if (callId != null && callId == currentCallId()) finishCall(reason)
    }

    private fun cancelReasonText(reason: String?): String = getString(
        when (reason) {
            "answered_elsewhere" -> R.string.ended_answered_elsewhere
            // Our own ringing ran out: we missed them. Otherwise we were the
            // one calling, and nobody picked up.
            "timeout" -> if (_callState.value is PhoneCallState.Ringing) R.string.ended_missed else R.string.ended_no_answer
            "peer_disconnected" -> R.string.ended_connection_lost
            "declined" -> R.string.ended_tv_declined
            "full" -> R.string.ended_call_full
            else -> R.string.ended_call
        }
    )

    private fun placeCall(mode: CallMode, fullScreenRemote: Boolean) {
        if (busy()) {
            Log.w(TAG, "already in a call, ignoring a second request")
            showCallScreen()
            return
        }
        if (!config.isConfigured) {
            _callState.value = PhoneCallState.Ended(getString(R.string.status_not_configured))
            bringCallScreenToFront()
            return
        }

        // Show the call screen straight away; waiting for the server would make
        // the button feel dead, and any error needs somewhere to appear.
        _callState.value = PhoneCallState.Placing
        showCallScreen()
        startStateTimeout(PLACING_TIMEOUT_MS) {
            if (_callState.value is PhoneCallState.Placing) finishCall(getString(R.string.ended_unreachable))
        }

        val connected = signalling.state.value == SignallingClient.State.CONNECTED
        if (!connected || !signalling.requestCall(mode, fullScreenRemote)) {
            // A request sent into a socket that is not open yet is simply lost,
            // so hold it until the connection is up.
            Log.i(TAG, "not connected yet, the call goes out once we are")
            pendingCall = PendingCall(mode, fullScreenRemote)
            signalling.connect()
        }
    }

    private fun onConnected() {
        pendingCall?.let { call ->
            pendingCall = null
            if (_callState.value is PhoneCallState.Placing) {
                Log.i(TAG, "socket came up, placing the queued call")
                signalling.requestCall(call.mode, call.fullScreenRemote)
            }
        }
        pendingAcceptCallId?.let { id ->
            pendingAcceptCallId = null
            if (id == currentCallId()) {
                Log.i(TAG, "socket came up, accepting queued call $id")
                signalling.acceptCall(id)
            }
        }
    }

    /** Start ringing, unless this call is already ringing or answered here. */
    private fun ring(callId: String, fromName: String, mode: CallMode) {
        val busyWith = currentCallId()
        // The socket and the push both deliver every invite, so the second copy
        // must not restart the ring - or un-answer an answered call.
        if (busyWith == callId) return
        if (busy()) {
            Log.w(TAG, "ignoring call $callId while busy with $busyWith")
            return
        }
        _callState.value = PhoneCallState.Ringing(callId, fromName, mode)
        showCallScreen()
        // The server cancels after 45 s. If that cancel never reaches us - no
        // signal, push lost - stop ringing anyway rather than ring forever.
        startStateTimeout(RING_GIVE_UP_MS) {
            finishIfCurrent(callId, getString(R.string.ended_missed))
        }
    }

    /**
     * Answering from a push often happens before the socket is back up, so the
     * accept is queued and replayed on connect rather than being lost.
     */
    private fun acceptCall(callId: String) {
        val ringing = _callState.value as? PhoneCallState.Ringing ?: return
        if (ringing.callId != callId) return

        // Stop the ringtone now; the server's reply can take a moment.
        _callState.value = PhoneCallState.Connecting(callId, ringing.fromName)
        startStateTimeout(PLACING_TIMEOUT_MS) {
            // Still no media means the server never answered the accept.
            if (engine == null) finishIfCurrent(callId, getString(R.string.ended_unreachable))
        }

        if (signalling.state.value == SignallingClient.State.CONNECTED && signalling.acceptCall(callId)) return
        Log.i(TAG, "queuing accept for $callId until the socket is up")
        pendingAcceptCallId = callId
        signalling.connect()
    }

    private fun startStateTimeout(ms: Long, onTimeout: () -> Unit) {
        stateTimeout?.cancel()
        stateTimeout = lifecycleScope.launch {
            delay(ms)
            onTimeout()
        }
    }

    // -----------------------------------------------------------------------

    private fun handleEvent(event: ServerEvent) {
        when (event) {
            is ServerEvent.CallStarted -> {
                if (_callState.value !is PhoneCallState.Placing) {
                    // Hung up before the server confirmed: cancel it there too.
                    signalling.hangup(event.callId, "cancelled")
                    return
                }
                stateTimeout?.cancel()
                _callState.value = PhoneCallState.Dialling(event.callId, event.ringTimeoutMs)
                showCallScreen()
            }

            is ServerEvent.IncomingCall -> ring(event.callId, event.fromName, event.mode)

            // Somebody answered, joined or left: connect to exactly the people
            // the server says are in the call.
            is ServerEvent.CallPeers -> {
                if (event.callId != currentCallId()) return
                val names = event.peers.joinToString(", ") { it.peerName }
                when (val state = _callState.value) {
                    is PhoneCallState.InCall -> _callState.value = state.copy(peerName = names)
                    else -> _callState.value = PhoneCallState.Connecting(event.callId, names)
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

            is ServerEvent.CallTimeout ->
                finishIfCurrent(event.callId, getString(R.string.ended_no_answer))

            is ServerEvent.CallCancelled ->
                finishIfCurrent(event.callId, cancelReasonText(event.reason))

            is ServerEvent.Error -> {
                Log.w(TAG, "server error ${event.code}: ${event.message}")
                val placing = _callState.value is PhoneCallState.Placing
                when {
                    event.code == "busy" && placing -> finishCall(getString(R.string.ended_tv_busy))
                    event.code == "full" && placing -> finishCall(getString(R.string.ended_call_full))
                    event.code == "no_targets" && placing -> finishCall(getString(R.string.ended_no_tv))
                    event.code == "unauthorised" && busy() -> finishCall(getString(R.string.status_unauthorised))
                }
            }

            // The server forgets an unanswered call whose caller has dropped,
            // and ends an answered one, so waiting would hang. A push-driven
            // ring, or an answer queued behind it, is left alone: the socket
            // is often still coming up, and the timeouts cover it failing.
            is ServerEvent.Disconnected -> when (_callState.value) {
                is PhoneCallState.Dialling -> finishCall(getString(R.string.ended_connection_lost))
                is PhoneCallState.Connecting ->
                    if (pendingAcceptCallId == null) finishCall(getString(R.string.ended_connection_lost))
                else -> Unit
            }

            // Kept whole (with each device's role) so the home screen can find
            // the TV, and offer to join a call the family is already in.
            is ServerEvent.Presence -> {
                _presence.value = event.devices
                _activeCall.value = event.call
            }

            else -> Unit
        }
    }

    // -----------------------------------------------------------------------

    /** Open the camera and microphone for a call. False if the call cannot go ahead. */
    private fun startMedia(callId: String): Boolean {
        // A duplicate accept must not leave a second engine holding the camera.
        releaseEngine()

        // Claim microphone/camera use for the length of the call only.
        startForegroundWithType("In a call", inCall = true)
        AudioModuleFactory.enterCallMode(this, speakerphone = true)

        val webrtc = WebRtcEngine(
            context = this,
            signalling = signalling,
            // A phone's mic and earpiece are the case the hardware canceller was
            // designed for, so here we let it do its job.
            preferSoftwareEchoCancellation = false
        )
        engine = webrtc

        webrtc.listener = object : WebRtcEngine.Listener {
            override fun onPeerStateChanged(peerId: String, state: PeerConnection.PeerConnectionState) {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        updateParticipant(peerId) { it.copy(connected = true) }
                        val connecting = _callState.value as? PhoneCallState.Connecting ?: return
                        _callState.value = PhoneCallState.InCall(callId, connecting.peerName)
                    }

                    // Losing grandpa ends the call. Losing another phone only
                    // takes them out of the picture.
                    PeerConnection.PeerConnectionState.FAILED -> {
                        val lost = _participants.value.firstOrNull { it.peerId == peerId }
                        if (lost == null || lost.role == "tv") {
                            signalling.hangup(callId, "failed")
                            finishCall(getString(R.string.ended_connection_lost))
                        } else {
                            Log.w(TAG, "lost the connection to ${lost.name}")
                            _participants.value = _participants.value.filter { it.peerId != peerId }
                            engine?.removePeer(peerId)
                        }
                    }

                    else -> Unit
                }
            }

            override fun onRemoteVideoTrack(peerId: String, track: VideoTrack) {
                updateParticipant(peerId) { it.copy(track = track) }
            }

            override fun onError(message: String) {
                Log.e(TAG, "webrtc: $message")
                _lastError.value = message
            }
        }

        // Every call starts on the front camera, as WhatsApp does.
        _frontCamera.value = true
        val camera = createCameraCapturer(front = true)
        capturer = camera
        val (width, height) = captureSize
        if (!webrtc.start(callId, camera, width, height)) {
            signalling.hangup(callId, "failed")
            finishCall(getString(R.string.ended_failed))
            return false
        }
        _localEngine.value = webrtc
        return true
    }

    private fun updateParticipant(peerId: String, change: (RemoteParticipant) -> RemoteParticipant) {
        _participants.value = _participants.value.map { if (it.peerId == peerId) change(it) else it }
    }

    private fun createCameraCapturer(front: Boolean): VideoCapturer? {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.w(TAG, "no camera permission, so this call is audio only")
            return null
        }
        val enumerator = Camera2Enumerator(this)
        val names = enumerator.deviceNames
        if (names.isEmpty()) {
            Log.e(TAG, "no cameras on this phone")
            return null
        }
        val preferred = names.firstOrNull {
            if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        } ?: names.first()
        _frontCamera.value = enumerator.isFrontFacing(preferred)
        captureSize = fullViewSize(enumerator, preferred)

        return runCatching { Camera2Capturer(this, preferred, null) }
            .onFailure { Log.e(TAG, "could not open camera $preferred", it) }
            .getOrNull()
    }

    /**
     * A phone camera's sensor is 4:3. Asking it for 16:9 video makes it cut a
     * band off the picture before anything is sent, so the far end saw a
     * narrower, zoomed-in view than the phone's own camera app shows. So pick
     * the largest 4:3 size up to 1440 wide, and fall back to 720p only on a
     * camera that offers no 4:3 size at all.
     */
    private fun fullViewSize(enumerator: Camera2Enumerator, deviceName: String): Pair<Int, Int> {
        val formats = runCatching { enumerator.getSupportedFormats(deviceName) }.getOrNull().orEmpty()
        val best = formats
            .filter { it.width <= 1440 && it.width * 3 == it.height * 4 }
            .maxByOrNull { it.width }
        Log.i(TAG, "camera $deviceName: sending ${best?.let { "${it.width}x${it.height}" } ?: "1280x720 (no 4:3 size)"}")
        return if (best != null) best.width to best.height else 1280 to 720
    }

    private fun switchCamera() {
        val camera = capturer as? CameraVideoCapturer ?: return
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            // Reported on the camera thread; the flow is safe to set from there.
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _frontCamera.value = isFrontCamera
            }

            override fun onCameraSwitchError(error: String) {
                Log.w(TAG, "camera switch failed: $error")
            }
        })
    }

    /** Stop the media. The UI is told first, so it lets go of the video before it is freed. */
    private fun releaseEngine() {
        _participants.value = emptyList()
        _localEngine.value = null
        engine?.release()
        engine = null
        capturer = null
    }

    private fun finishCall(reason: String) {
        stateTimeout?.cancel()
        pendingCall = null
        pendingAcceptCallId = null
        releaseEngine()
        _muted.value = false

        AudioModuleFactory.leaveCallMode(this)
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)

        _callState.value = PhoneCallState.Ended(reason)
        // Drop the microphone/camera claim now the call is over.
        startForegroundWithType("Ready")
    }

    // -----------------------------------------------------------------------

    /**
     * Raise the call screen. A full-screen intent on a high-importance channel
     * is what lets this appear over the lock screen from the background - the
     * same mechanism the system dialer uses.
     */
    private fun showCallScreen() {
        val fullScreen = PendingIntent.getActivity(
            this, 1, callScreenIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val state = _callState.value
        val title = when (state) {
            is PhoneCallState.Ringing -> getString(R.string.incoming_from, state.fromName)
            is PhoneCallState.Placing, is PhoneCallState.Dialling -> getString(R.string.calling)
            else -> getString(R.string.call_in_progress)
        }

        // Only an incoming call needs the loud channel. A call we placed was
        // started from our own screen, and a pop-up banner would just cover it.
        val incoming = state is PhoneCallState.Ringing
        val notification = Notification.Builder(this, if (incoming) CHANNEL_CALLS else CHANNEL_STATUS)
            .setContentTitle(title)
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setCategory(Notification.CATEGORY_CALL)
            .apply { if (incoming) setFullScreenIntent(fullScreen, true) }
            // Tapping it goes back to the call, e.g. after pressing Back.
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, notification)
        bringCallScreenToFront()
    }

    private fun bringCallScreenToFront() {
        runCatching { startActivity(callScreenIntent()) }
            .onFailure { Log.d(TAG, "direct start blocked; full-screen intent will handle it") }
    }

    private fun callScreenIntent() = Intent(this, PhoneCallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        // Must be HIGH, or Android ignores the full-screen intent and the phone
        // never rings properly.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(true)
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    private fun buildStatusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /**
     * Android 14+ lets a foreground service claim microphone or camera use only
     * while the app holds those permissions and is on screen; otherwise
     * startForeground throws and the whole app crashes - which it did whenever
     * this service started before a screen was showing. So the service normally
     * claims only remoteMessaging, which keeps the call socket alive, and adds
     * microphone/camera for the length of a call, when the call screen is up. If
     * Android still refuses, the call carries on and the reason is logged.
     */
    private fun startForegroundWithType(text: String, inCall: Boolean = false) {
        val notification = buildStatusNotification(text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_NOTIFICATION_ID, notification)
            return
        }
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        var types = base
        if (inCall && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (inCall && hasPermission(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            startForeground(STATUS_NOTIFICATION_ID, notification, types)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not claim foreground types $types, retrying without microphone/camera: ${e.message}")
            if (types != base) {
                runCatching { startForeground(STATUS_NOTIFICATION_ID, notification, base) }
                    .onFailure { Log.e(TAG, "could not run as a foreground service at all", it) }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        stateTimeout?.cancel()
        releaseEngine()
        signalling.disconnect()
        AudioModuleFactory.leaveCallMode(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneIntercom"
        private const val CHANNEL_STATUS = "phone_status"
        private const val CHANNEL_CALLS = "phone_calls"
        private const val STATUS_NOTIFICATION_ID = 10
        private const val CALL_NOTIFICATION_ID = 11

        /** Long enough to wake the radio and reconnect; the server itself replies at once. */
        private const val PLACING_TIMEOUT_MS = 20_000L

        /** A little past the server's 45 s ring timeout. */
        private const val RING_GIVE_UP_MS = 60_000L

        const val ACTION_CONNECT = "phone.CONNECT"
        const val ACTION_SETTINGS_CHANGED = "phone.SETTINGS_CHANGED"
        const val ACTION_CALL_TV = "phone.CALL_TV"
        const val ACTION_SHOW_ME_ON_TV = "phone.SHOW_ME_ON_TV"
        const val ACTION_CHECK_IN = "phone.CHECK_IN"
        const val ACTION_PUSH_RING = "phone.PUSH_RING"
        const val ACTION_ACCEPT = "phone.ACCEPT"
        const val ACTION_DECLINE = "phone.DECLINE"
        const val ACTION_HANGUP = "phone.HANGUP"
        const val ACTION_SWITCH_CAMERA = "phone.SWITCH_CAMERA"
        const val ACTION_TOGGLE_MUTE = "phone.TOGGLE_MUTE"
        const val ACTION_REMOTE_CANCELLED = "phone.REMOTE_CANCELLED"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_REASON = "reason"
        private const val EXTRA_FROM_NAME = "fromName"
        private const val EXTRA_MODE = "mode"

        private val _callState = MutableStateFlow<PhoneCallState>(PhoneCallState.Idle)
        val callState: StateFlow<PhoneCallState> = _callState.asStateFlow()

        private val _connectionState = MutableStateFlow(SignallingClient.State.IDLE)
        val connectionState: StateFlow<SignallingClient.State> = _connectionState.asStateFlow()

        /** Everyone else in the call, with their pictures as they arrive. */
        private val _participants = MutableStateFlow<List<RemoteParticipant>>(emptyList())
        val participants: StateFlow<List<RemoteParticipant>> = _participants.asStateFlow()

        private val _localEngine = MutableStateFlow<WebRtcEngine?>(null)
        val localEngine: StateFlow<WebRtcEngine?> = _localEngine.asStateFlow()

        private val _muted = MutableStateFlow(false)
        val muted: StateFlow<Boolean> = _muted.asStateFlow()

        /** The self-view is mirrored only for the front camera, as in a mirror. */
        private val _frontCamera = MutableStateFlow(true)
        val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

        private val _presence = MutableStateFlow<List<DevicePresence>>(emptyList())
        val presence: StateFlow<List<DevicePresence>> = _presence.asStateFlow()

        /** The call going on in the household, if any - whether or not we are in it. */
        private val _activeCall = MutableStateFlow<CallSummary?>(null)
        val activeCall: StateFlow<CallSummary?> = _activeCall.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun send(context: Context, action: String, callId: String? = null, reason: String? = null) {
            val intent = Intent(context, PhoneIntercomService::class.java).setAction(action)
            callId?.let { intent.putExtra(EXTRA_CALL_ID, it) }
            reason?.let { intent.putExtra(EXTRA_REASON, it) }
            context.startForegroundService(intent)
        }

        /** Called from the FCM receiver: a call is incoming, ring and get the socket up now. */
        fun onPushReceived(context: Context, callId: String, fromName: String, mode: CallMode) {
            context.startForegroundService(
                Intent(context, PhoneIntercomService::class.java)
                    .setAction(ACTION_PUSH_RING)
                    .putExtra(EXTRA_CALL_ID, callId)
                    .putExtra(EXTRA_FROM_NAME, fromName)
                    .putExtra(EXTRA_MODE, mode.wire)
            )
        }

        fun clearEnded() {
            if (_callState.value is PhoneCallState.Ended) _callState.value = PhoneCallState.Idle
        }
    }
}
