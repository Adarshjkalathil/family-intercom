package com.kalathil.intercom.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire protocol shared with server/src/index.js.
 *
 * Hand-rolled with org.json rather than a serialization library: the message set
 * is small and fixed, and it keeps the dependency list short for an app that has
 * to survive years of Android updates with nobody maintaining it.
 */

enum class CallMode(val wire: String) {
    /** Rings, and waits for someone to accept. */
    NORMAL("normal"),

    /**
     * Auto-answers at the TV. Always announced with a chime and an on-screen
     * border for the whole duration - see TvCallActivity.
     */
    EMERGENCY("emergency");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

data class IceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)

data class DevicePresence(
    val deviceId: String,
    val role: String,
    val displayName: String,
    val online: Boolean
)

/** The call going on in the household right now, as presence reports it. */
data class CallSummary(
    val callId: String,
    /** Somebody has answered, so joining connects straight away. */
    val active: Boolean,
    val memberIds: List<String>
)

/** Another member of a call we are in. */
data class CallPeer(
    val peerId: String,
    val peerName: String,
    val role: String,
    /** True when we make the WebRTC offer to this peer; false when we wait for theirs. */
    val offer: Boolean
)

/** Everything the server can send us. */
sealed interface ServerEvent {
    data class Registered(val deviceId: String, val iceServers: List<IceServer>) : ServerEvent
    data class Presence(val devices: List<DevicePresence>, val call: CallSummary?) : ServerEvent
    data class IceServersRefreshed(val iceServers: List<IceServer>) : ServerEvent

    data class CallStarted(val callId: String, val ringTimeoutMs: Long) : ServerEvent
    data class IncomingCall(
        val callId: String,
        val fromDeviceId: String,
        val fromName: String,
        val mode: CallMode,
        val fullScreenRemote: Boolean
    ) : ServerEvent

    /**
     * Everyone else in a call we are in, re-sent whenever someone joins or
     * leaves. Connect to each peer listed and drop any that is no longer listed.
     */
    data class CallPeers(
        val callId: String,
        val mode: CallMode,
        val fullScreenRemote: Boolean,
        val peers: List<CallPeer>
    ) : ServerEvent

    data class CallCancelled(val callId: String, val reason: String) : ServerEvent
    data class CallTimeout(val callId: String) : ServerEvent
    data class RejectedBy(val callId: String, val deviceId: String) : ServerEvent

    data class Signal(val callId: String, val from: String, val payload: JSONObject) : ServerEvent

    data class Error(val code: String, val message: String) : ServerEvent

    /** Connection lifecycle, surfaced as events so the UI can show honest state. */
    data object Connected : ServerEvent
    data class Disconnected(val reason: String) : ServerEvent
}

internal object Protocol {

    fun register(
        deviceId: String,
        role: String,
        displayName: String,
        secret: String,
        fcmToken: String?
    ): String = JSONObject().apply {
        put("type", "register")
        put("deviceId", deviceId)
        put("role", role)
        put("displayName", displayName)
        put("secret", secret)
        fcmToken?.let { put("fcmToken", it) }
    }.toString()

    fun callRequest(mode: CallMode, fullScreenRemote: Boolean): String = JSONObject().apply {
        put("type", "call-request")
        put("mode", mode.wire)
        put("fullScreenRemote", fullScreenRemote)
    }.toString()

    fun simple(type: String, callId: String, reason: String? = null): String = JSONObject().apply {
        put("type", type)
        put("callId", callId)
        reason?.let { put("reason", it) }
    }.toString()

    fun signal(callId: String, to: String, payload: JSONObject): String = JSONObject().apply {
        put("type", "signal")
        put("callId", callId)
        put("to", to)
        put("payload", payload)
    }.toString()

    fun updateToken(token: String): String = JSONObject().apply {
        put("type", "update-token")
        put("fcmToken", token)
    }.toString()

    fun refreshIce(): String = JSONObject().put("type", "refresh-ice").toString()

    /** Returns null for anything we do not understand, so a newer server cannot crash an older app. */
    fun parse(text: String): ServerEvent? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (json.optString("type")) {
            "registered" -> ServerEvent.Registered(
                deviceId = json.optString("deviceId"),
                iceServers = parseIceServers(json.optJSONArray("iceServers"))
            )

            "ice-servers" -> ServerEvent.IceServersRefreshed(
                parseIceServers(json.optJSONArray("iceServers"))
            )

            "presence" -> ServerEvent.Presence(
                devices = buildList {
                    val arr = json.optJSONArray("devices") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val d = arr.optJSONObject(i) ?: continue
                        add(
                            DevicePresence(
                                deviceId = d.optString("deviceId"),
                                role = d.optString("role"),
                                displayName = d.optString("displayName"),
                                online = d.optBoolean("online")
                            )
                        )
                    }
                },
                call = json.optJSONObject("call")?.let { c ->
                    CallSummary(
                        callId = c.optString("callId"),
                        active = c.optBoolean("active"),
                        memberIds = c.optJSONArray("members").strings()
                    )
                }
            )

            "call-started" -> ServerEvent.CallStarted(
                callId = json.optString("callId"),
                ringTimeoutMs = json.optLong("ringTimeoutMs", 45_000L)
            )

            "incoming-call" -> ServerEvent.IncomingCall(
                callId = json.optString("callId"),
                fromDeviceId = json.optString("from"),
                fromName = json.optString("fromName"),
                mode = CallMode.from(json.optString("mode")),
                fullScreenRemote = json.optBoolean("fullScreenRemote")
            )

            "call-peers" -> ServerEvent.CallPeers(
                callId = json.optString("callId"),
                mode = CallMode.from(json.optString("mode")),
                fullScreenRemote = json.optBoolean("fullScreenRemote"),
                peers = buildList {
                    val arr = json.optJSONArray("peers") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        val id = p.optString("peerId").ifBlank { null } ?: continue
                        add(
                            CallPeer(
                                peerId = id,
                                peerName = p.optString("peerName"),
                                role = p.optString("role", "phone"),
                                offer = p.optBoolean("offer")
                            )
                        )
                    }
                }
            )

            "call-cancelled" -> ServerEvent.CallCancelled(
                callId = json.optString("callId"),
                reason = json.optString("reason")
            )

            "call-timeout" -> ServerEvent.CallTimeout(json.optString("callId"))

            "call-rejected-by" -> ServerEvent.RejectedBy(
                callId = json.optString("callId"),
                deviceId = json.optString("deviceId")
            )

            "signal" -> ServerEvent.Signal(
                callId = json.optString("callId"),
                from = json.optString("from"),
                payload = json.optJSONObject("payload") ?: JSONObject()
            )

            "error" -> ServerEvent.Error(
                code = json.optString("code"),
                message = json.optString("message")
            )

            else -> null
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).ifBlank { null } }
    }

    private fun parseIceServers(arr: JSONArray?): List<IceServer> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urls = when (val u = o.opt("urls")) {
                    is String -> listOf(u)
                    is JSONArray -> (0 until u.length()).mapNotNull { u.optString(it).ifBlank { null } }
                    else -> emptyList()
                }
                if (urls.isEmpty()) continue
                add(
                    IceServer(
                        urls = urls,
                        username = o.optString("username").ifBlank { null },
                        credential = o.optString("credential").ifBlank { null }
                    )
                )
            }
        }
    }
}
