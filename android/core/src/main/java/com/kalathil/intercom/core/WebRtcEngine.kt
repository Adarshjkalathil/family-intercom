package com.kalathil.intercom.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * The media side of a call, wrapped so the two apps only have to say "here is
 * my camera" and "these are the people in the call".
 *
 * A group call is a mesh: one peer connection to each other member, all sharing
 * our one camera and microphone. The media never touches our server. The
 * signalling server introduces the peers; the relay only forwards packets it
 * cannot decrypt, and only when CGNAT leaves no direct path.
 */
class WebRtcEngine(
    context: Context,
    private val signalling: SignallingClient,
    /**
     * The TV's mic (on the webcam) and its speakers are different devices, so
     * the platform's hardware echo canceller - tuned for a phone's own earpiece
     * and mic - does not help and often makes things worse. Turning it off lets
     * WebRTC's own AEC3 run, which at least knows about both streams.
     */
    private val preferSoftwareEchoCancellation: Boolean
) {

    /** Everything here is reported on the main thread, and only for peers still in the call. */
    interface Listener {
        fun onPeerStateChanged(peerId: String, state: PeerConnection.PeerConnectionState)
        fun onRemoteVideoTrack(peerId: String, track: VideoTrack)
        fun onError(message: String)
    }

    var listener: Listener? = null

    val eglBase: EglBase = EglBase.create()

    private val appContext = context.applicationContext
    private val factory: PeerConnectionFactory

    private var videoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var callId: String? = null

    /** One connection per other member of the call, by their device id. Main thread only. */
    private val peers = mutableMapOf<String, PeerLink>()

    /**
     * WebRTC reports everything on its own signalling thread, and disposing a
     * PeerConnection from that thread deadlocks it. So every callback is moved to
     * the main thread before anything acts on it.
     */
    private val main = Handler(Looper.getMainLooper())

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val audioModule = AudioModuleFactory.create(appContext, preferSoftwareEchoCancellation)

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(
                // Hardware codecs where available; the fallback matters on cheap
                // TV chipsets whose encoders lie about what they support.
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory ready (software AEC: $preferSoftwareEchoCancellation)")
    }

    // -----------------------------------------------------------------------
    // Call lifecycle
    // -----------------------------------------------------------------------

    /**
     * Open the camera and microphone for a call. Nobody is connected yet: the
     * server's peer list does that, through [syncPeers].
     *
     * @param capturer the camera. Null means audio-only, which is what happens
     *   when grandpa has switched the camera off or the TV cannot open the webcam.
     * @return false if the call cannot go ahead; [Listener.onError] says why.
     */
    fun start(
        callId: String,
        capturer: VideoCapturer?,
        videoWidth: Int = 1280,
        videoHeight: Int = 720,
        videoFps: Int = 24
    ): Boolean {
        stop()
        if (signalling.iceServers.isEmpty()) {
            listener?.onError("No ICE servers - the server did not send any. Check the connection.")
            return false
        }
        this.callId = callId
        attachAudio()
        capturer?.let { attachVideo(it, videoWidth, videoHeight, videoFps) }
        return true
    }

    /**
     * Make our connections match the server's list of who is in the call:
     * connect to anyone new, and hang up on anyone who has left.
     */
    fun syncPeers(wanted: List<CallPeer>) {
        if (callId == null) return
        val ids = wanted.map { it.peerId }.toSet()
        (peers.keys - ids).forEach { removePeer(it) }
        for (peer in wanted) {
            if (peer.peerId !in peers) addPeer(peer)
        }
        applyVideoLimits()
    }

    /** Drop one member - they left, or the connection to them failed for good. */
    fun removePeer(peerId: String) {
        val link = peers.remove(peerId) ?: return
        Log.i(TAG, "closing the connection to ${peerId.take(8)}")
        link.close()
        applyVideoLimits()
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = signalling.iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.let { setUsername(it) }
                server.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Both ends are behind CGNAT, so gathering everything and letting
            // ICE sort it out is exactly the right trade.
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Keeps a call alive across a Wi-Fi to mobile-data handover instead
            // of dropping it, which matters when you answer while walking out.
            enableImplicitRollback = true
            keyType = PeerConnection.KeyType.ECDSA
        }
    }

    private fun addPeer(peer: CallPeer) {
        val link = PeerLink(peer.peerId)
        val pc = factory.createPeerConnection(rtcConfig(), PeerObserver(link)) ?: run {
            listener?.onError("Could not create a connection to ${peer.peerName}")
            return
        }
        link.pc = pc
        peers[peer.peerId] = link
        Log.i(TAG, "connecting to ${peer.peerName} (${peer.role}), ${if (peer.offer) "we offer" else "they offer"}")

        // The same camera and microphone go to everyone.
        localAudioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        localVideoTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        if (peer.offer) createOffer(link)
    }

    private fun attachAudio() {
        val constraints = MediaConstraints().apply {
            // These are the legacy goog* constraints. They are still what the
            // Android audio processing module reads, and turning them on is the
            // difference between a usable speakerphone call and a howl.
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        audioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }
    }

    private fun attachVideo(capturer: VideoCapturer, width: Int, height: Int, fps: Int) {
        videoCapturer = capturer
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(capturer.isScreencast)

        capturer.initialize(surfaceHelper, appContext, videoSource!!.capturerObserver)
        runCatching { capturer.startCapture(width, height, fps) }
            .onFailure {
                Log.e(TAG, "startCapture failed", it)
                listener?.onError("Camera would not start: ${it.message}")
            }

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }
    }

    /**
     * Every extra person means our camera is encoded and uploaded once more, so
     * each copy gets smaller as the call grows, for any number of people: the
     * copies share about 2 Mbps, and past five the picture also gets smaller
     * and slower. One-to-one calls are left to WebRTC's own judgement. A budget
     * TV's encoder, and a phone's upload, are what this protects.
     */
    private fun applyVideoLimits() {
        val others = peers.size
        val maxBitrate = if (others <= 1) null else maxOf(TOTAL_UPLOAD_BPS / others, MIN_COPY_BPS)
        val scaleDown = when {
            others <= 2 -> 1.0
            others == 3 -> 1.5
            others <= 5 -> 2.0
            else -> 3.0
        }
        val maxFps = if (others > 5) 15 else null
        for (link in peers.values) {
            val pc = link.pc ?: continue
            for (sender in pc.senders) {
                if (sender.track()?.kind() != MediaStreamTrack.VIDEO_TRACK_KIND) continue
                runCatching {
                    val params = sender.parameters
                    if (params.encodings.isEmpty()) return@runCatching
                    for (encoding in params.encodings) {
                        encoding.maxBitrateBps = maxBitrate
                        encoding.scaleResolutionDownBy = scaleDown
                        encoding.maxFramerate = maxFps
                    }
                    sender.setParameters(params)
                }.onFailure { Log.w(TAG, "could not limit video to ${link.peerId.take(8)}", it) }
            }
        }
    }

    /** Show our own camera in a local preview. */
    fun bindLocalPreview(renderer: SurfaceViewRenderer) {
        localVideoTrack?.addSink(renderer)
    }

    fun unbindLocalPreview(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
    }

    // -----------------------------------------------------------------------
    // SDP
    // -----------------------------------------------------------------------

    private fun createOffer(link: PeerLink) {
        val pc = link.pc ?: return
        pc.createOffer(object : SimpleSdpObserver(link, "createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) = link.onMain { current ->
                current.setLocalDescription(SimpleSdpObserver(link, "setLocal(offer)"), sdp)
                sendSignal(link.peerId, "offer", sdp)
            }
        }, mediaConstraints())
    }

    private fun createAnswer(link: PeerLink) {
        val pc = link.pc ?: return
        pc.createAnswer(object : SimpleSdpObserver(link, "createAnswer") {
            override fun onCreateSuccess(sdp: SessionDescription) = link.onMain { current ->
                current.setLocalDescription(SimpleSdpObserver(link, "setLocal(answer)"), sdp)
                sendSignal(link.peerId, "answer", sdp)
            }
        }, mediaConstraints())
    }

    private fun mediaConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    /** Feed in a `signal` event from the server. */
    fun handleSignal(from: String, payload: JSONObject) {
        val link = peers[from]
        val pc = link?.pc
        if (link == null || pc == null) {
            Log.d(TAG, "ignoring a signal from ${from.take(8)}, who is not in our call")
            return
        }
        when (payload.optString("kind")) {
            "offer" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                pc.setRemoteDescription(object : SimpleSdpObserver(link, "setRemote(offer)") {
                    override fun onSetSuccess() = link.onMain {
                        link.onRemoteDescriptionSet()
                        createAnswer(link)
                    }
                }, sdp)
            }

            "answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                pc.setRemoteDescription(object : SimpleSdpObserver(link, "setRemote(answer)") {
                    override fun onSetSuccess() = link.onMain { link.onRemoteDescriptionSet() }
                }, sdp)
            }

            "ice" -> {
                val candidate = IceCandidate(
                    payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),
                    payload.optString("candidate")
                )
                if (link.remoteDescriptionSet) {
                    pc.addIceCandidate(candidate)
                } else {
                    link.pendingCandidates.add(candidate)
                }
            }

            else -> Log.d(TAG, "ignoring signal payload: $payload")
        }
    }

    private fun sendSignal(to: String, kind: String, sdp: SessionDescription) {
        val id = callId ?: return
        signalling.sendSignal(id, to, JSONObject().apply {
            put("kind", kind)
            put("sdp", sdp.description)
        })
    }

    // -----------------------------------------------------------------------

    fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    /** Tear everything down. Safe to call twice. Main thread only. */
    fun stop() {
        // Closing each link first means anything WebRTC has already queued for
        // it is dropped rather than acted on.
        peers.values.toList().forEach { it.close() }
        peers.clear()

        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoCapturer = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null

        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null

        callId = null
    }

    /** Release the factory too. Only on app teardown - a new engine is needed afterwards. */
    fun release() {
        stop()
        factory.dispose()
        eglBase.release()
    }

    // -----------------------------------------------------------------------

    /** The connection to one other member of the call. */
    private inner class PeerLink(val peerId: String) {
        var pc: PeerConnection? = null

        /**
         * ICE candidates can arrive before the remote description is set, and
         * PeerConnection rejects them if they do. Holding them here and flushing
         * afterwards removes a race that otherwise shows up as calls that
         * connect only sometimes.
         */
        val pendingCandidates = mutableListOf<IceCandidate>()
        var remoteDescriptionSet = false

        /**
         * Run [block] on the main thread with this link's connection - unless the
         * link has been closed or replaced since, in which case a late "connection
         * failed" from it must not touch whoever is in the call now.
         */
        fun onMain(block: (PeerConnection) -> Unit) {
            main.post {
                val current = pc
                if (current != null && peers[peerId] === this) block(current)
            }
        }

        fun onRemoteDescriptionSet() {
            remoteDescriptionSet = true
            if (pendingCandidates.isNotEmpty()) {
                Log.d(TAG, "flushing ${pendingCandidates.size} buffered ICE candidate(s)")
                pendingCandidates.forEach { pc?.addIceCandidate(it) }
                pendingCandidates.clear()
            }
        }

        fun close() {
            val current = pc
            pc = null
            pendingCandidates.clear()
            current?.dispose()
        }
    }

    private inner class PeerObserver(private val link: PeerLink) : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) = link.onMain {
            val id = callId ?: return@onMain
            signalling.sendSignal(id, link.peerId, JSONObject().apply {
                put("kind", "ice")
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection to ${link.peerId.take(8)}: $newState")
            link.onMain {
                // Sending parameters only take once there is something to send.
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) applyVideoLimits()
                listener?.onPeerStateChanged(link.peerId, newState)
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is VideoTrack) {
                Log.i(TAG, "video arrived from ${link.peerId.take(8)}")
                link.onMain { listener?.onRemoteVideoTrack(link.peerId, track) }
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE to ${link.peerId.take(8)}: $state")
            if (state == PeerConnection.IceConnectionState.FAILED) {
                // Almost always symmetric NAT on both ends with no working relay.
                link.onMain {
                    listener?.onError("Could not find a path to the other device. Check that the TURN relay is running.")
                }
            }
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            Log.i(TAG, "using candidate pair to ${link.peerId.take(8)}: ${event.local.sdp} <-> ${event.remote.sdp}")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    /** Reports failures for one link, and only while that link is still in the call. */
    private open inner class SimpleSdpObserver(
        private val link: PeerLink,
        private val tag: String
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            Log.e(TAG, "$tag create failed: $error")
            link.onMain { listener?.onError("$tag failed: $error") }
        }

        override fun onSetFailure(error: String) {
            Log.e(TAG, "$tag set failed: $error")
            link.onMain { listener?.onError("$tag failed: $error") }
        }
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        private const val STREAM_ID = "intercom"
        private const val AUDIO_TRACK_ID = "intercom-audio"
        private const val VIDEO_TRACK_ID = "intercom-video"
        /** What all the copies of our camera share in a group call. */
        private const val TOTAL_UPLOAD_BPS = 2_000_000
        /** Below this a copy is mush; better a big call runs a little over. */
        private const val MIN_COPY_BPS = 150_000
    }
}
