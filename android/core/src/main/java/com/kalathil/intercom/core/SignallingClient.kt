package com.kalathil.intercom.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Persistent WebSocket connection to the signalling server.
 *
 * Reconnects forever with backoff. On the TV this socket is expected to stay up
 * for months at a time behind a foreground service; on a phone it comes and goes.
 * Either way, a dropped socket must never be a silent failure - if grandpa's TV
 * quietly loses its connection, the button stops working and nobody finds out
 * until it matters.
 */
class SignallingClient(
    private val config: Config,
    private val role: String,
    private val scope: CoroutineScope
) {

    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        WAITING_TO_RETRY,

        /** The server refused the home secret. Nothing retries until the settings change. */
        UNAUTHORISED
    }

    private val client = OkHttpClient.Builder()
        // The server pings every 25s. Ours is a belt-and-braces ping from the
        // client side, because mobile NATs drop idle flows aggressively.
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _events = MutableSharedFlow<ServerEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var iceServers: List<IceServer> = emptyList()
        private set

    private var socket: WebSocket? = null
    private var retryJob: Job? = null
    private var iceRefreshJob: Job? = null
    private var connectWatchdog: Job? = null
    private var attempt = 0
    private var wantConnection = false

    // -----------------------------------------------------------------------

    fun connect() {
        if (!config.isConfigured) {
            Log.w(TAG, "connect() ignored: app is not configured yet")
            return
        }
        wantConnection = true
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) return
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        retryJob?.cancel()
        iceRefreshJob?.cancel()
        connectWatchdog?.cancel()
        // Forgetting the socket first makes its late callbacks stale (see Listener).
        val old = socket
        socket = null
        old?.close(1000, "client shutting down")
        _state.value = State.IDLE
    }

    /**
     * Drop the current connection and open a fresh one. Settings use this:
     * a new address, secret or name only reaches the server when it registers.
     */
    fun reconnect() {
        disconnect()
        attempt = 0
        connect()
    }

    private fun openSocket() {
        retryJob?.cancel()
        _state.value = State.CONNECTING
        val request = Request.Builder().url(config.serverUrl).build()
        Log.i(TAG, "connecting to ${config.serverUrl} (attempt ${attempt + 1})")
        val opening = client.newWebSocket(request, Listener())
        socket = opening

        // The read timeout is off so an idle socket can live for days, which
        // also means a handshake that stalls half-way - a slow or filtering
        // network - would wait forever and never retry. Cap it here instead;
        // cancelling reports a failure, and that schedules the retry.
        connectWatchdog?.cancel()
        connectWatchdog = scope.launch {
            delay(CONNECT_GIVE_UP_MS)
            if (socket === opening && _state.value == State.CONNECTING) {
                Log.w(TAG, "no answer from the server in ${CONNECT_GIVE_UP_MS / 1000}s, giving up on this attempt")
                opening.cancel()
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        if (!wantConnection) return
        _state.value = State.WAITING_TO_RETRY
        retryJob?.cancel()

        // 1s, 2s, 4s … capped at 30s, with jitter so several devices coming back
        // after a power cut don't all hammer the server on the same tick.
        val base = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        val wait = base + Random.nextLong(0, 1_000)
        attempt++

        Log.w(TAG, "disconnected ($reason), retrying in ${wait}ms")
        retryJob = scope.launch {
            delay(wait)
            if (wantConnection) openSocket()
        }
    }

    /**
     * TURN credentials expire 12 hours after they are issued (server/src/turn.js),
     * but the TV keeps one socket open for days. Without a refresh, calls that
     * need the relay start failing half a day after the last reconnect.
     */
    private fun scheduleIceRefresh() {
        iceRefreshJob?.cancel()
        iceRefreshJob = scope.launch {
            while (true) {
                delay(ICE_REFRESH_MS)
                refreshIceServers()
            }
        }
    }

    // -----------------------------------------------------------------------

    private fun emit(event: ServerEvent) {
        if (!_events.tryEmit(event)) {
            // A full buffer means nothing is collecting; better to know than to
            // lose a call event silently.
            Log.e(TAG, "event buffer full, dropped $event")
        }
    }

    private fun sendRaw(text: String): Boolean {
        val ok = socket?.send(text) ?: false
        if (!ok) Log.w(TAG, "send failed, socket is not open: ${text.take(60)}")
        return ok
    }

    // -- outgoing messages ---------------------------------------------------

    fun requestCall(mode: CallMode, fullScreenRemote: Boolean = false) =
        sendRaw(Protocol.callRequest(mode, fullScreenRemote))

    fun acceptCall(callId: String) = sendRaw(Protocol.simple("call-accept", callId))

    fun rejectCall(callId: String) = sendRaw(Protocol.simple("call-reject", callId))

    fun hangup(callId: String, reason: String = "hangup") =
        sendRaw(Protocol.simple("hangup", callId, reason))

    fun sendSignal(callId: String, to: String, payload: JSONObject) =
        sendRaw(Protocol.signal(callId, to, payload))

    fun updateFcmToken(token: String) = sendRaw(Protocol.updateToken(token))

    fun refreshIceServers() = sendRaw(Protocol.refreshIce())

    // -----------------------------------------------------------------------

    /**
     * OkHttp calls these on its own threads. Each one is moved onto [scope] so
     * the connection state is only ever touched from one thread, and ignored if
     * it comes from a socket we have already replaced - otherwise the old
     * socket closing after a reconnect would wipe out the new one and start a
     * second, duplicate connection.
     */
    private inner class Listener : WebSocketListener() {

        private fun onCurrent(webSocket: WebSocket, block: () -> Unit) {
            scope.launch { if (webSocket === socket) block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = onCurrent(webSocket) {
            Log.i(TAG, "socket open, registering as $role")
            attempt = 0
            webSocket.send(
                Protocol.register(
                    deviceId = config.deviceId,
                    role = role,
                    displayName = config.displayName,
                    secret = config.homeSecret,
                    fcmToken = config.fcmToken
                )
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onCurrent(webSocket) {
            val event = Protocol.parse(text)
            if (event == null) {
                Log.d(TAG, "ignoring unknown message: ${text.take(120)}")
                return@onCurrent
            }
            when (event) {
                is ServerEvent.Registered -> {
                    iceServers = event.iceServers
                    _state.value = State.CONNECTED
                    scheduleIceRefresh()
                    Log.i(TAG, "registered, ${event.iceServers.size} ICE server(s)")
                    emit(ServerEvent.Connected)
                }

                is ServerEvent.IceServersRefreshed -> iceServers = event.iceServers

                is ServerEvent.Error ->
                    Log.w(TAG, "server error ${event.code}: ${event.message}")

                else -> Unit
            }
            emit(event)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onCurrent(webSocket) {
            socket = null
            iceRefreshJob?.cancel()
            val reason = t.message ?: t.javaClass.simpleName
            emit(ServerEvent.Disconnected(reason))
            scheduleRetry(reason)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onCurrent(webSocket) {
            socket = null
            iceRefreshJob?.cancel()
            emit(ServerEvent.Disconnected("closed $code $reason"))

            // 4003 is "bad home secret". Retrying forever with credentials the
            // server has already refused just burns battery and fills the log.
            if (code == CLOSE_UNAUTHORISED) {
                Log.e(TAG, "server rejected our credentials - check the home secret in Settings")
                wantConnection = false
                _state.value = State.UNAUTHORISED
                emit(ServerEvent.Error("unauthorised", "The server rejected this device's home secret"))
                return@onCurrent
            }
            scheduleRetry("closed $code")
        }
    }

    private companion object {
        const val TAG = "Signalling"
        const val CLOSE_UNAUTHORISED = 4003

        /** Covers connecting, TLS and registering; a healthy server takes about a second. */
        const val CONNECT_GIVE_UP_MS = 30_000L

        /** A third of the server's 12h TURN credential lifetime. */
        const val ICE_REFRESH_MS = 4 * 60 * 60 * 1000L
    }
}
