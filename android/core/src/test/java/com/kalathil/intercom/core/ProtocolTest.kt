package com.kalathil.intercom.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire protocol is the contract between these apps and the server, and it is
 * the one part of the Android side that can be tested without a device. The
 * fixtures below are copied from what server/src/index.js actually sends.
 */
class ProtocolTest {

    @Test
    fun `parses a registration reply with ICE servers`() {
        val json = """
            {
              "type": "registered",
              "deviceId": "tv-1",
              "expiresAt": 1789000000000,
              "iceServers": [
                { "urls": ["stun:example.org:3478"] },
                {
                  "urls": ["turn:example.org:3478?transport=udp", "turns:example.org:5349?transport=tcp"],
                  "username": "1789000000:intercom",
                  "credential": "abc123="
                }
              ]
            }
        """.trimIndent()

        val event = Protocol.parse(json) as ServerEvent.Registered
        assertEquals("tv-1", event.deviceId)
        assertEquals(2, event.iceServers.size)

        val stun = event.iceServers[0]
        assertEquals(listOf("stun:example.org:3478"), stun.urls)
        assertNull("STUN needs no credentials", stun.username)

        val turn = event.iceServers[1]
        assertEquals(2, turn.urls.size)
        assertEquals("1789000000:intercom", turn.username)
        assertEquals("abc123=", turn.credential)
    }

    @Test
    fun `handles a urls field sent as a bare string`() {
        // The spec allows either form; being strict here would break calls for
        // no good reason.
        val event = Protocol.parse(
            """{"type":"registered","deviceId":"x","iceServers":[{"urls":"stun:a.b:3478"}]}"""
        ) as ServerEvent.Registered
        assertEquals(listOf("stun:a.b:3478"), event.iceServers[0].urls)
    }

    @Test
    fun `parses an incoming call`() {
        val event = Protocol.parse(
            """
            {"type":"incoming-call","callId":"c-1","from":"tv-1","fromName":"Living Room TV",
             "mode":"emergency","fullScreenRemote":true}
            """.trimIndent()
        ) as ServerEvent.IncomingCall

        assertEquals("c-1", event.callId)
        assertEquals("Living Room TV", event.fromName)
        assertEquals(CallMode.EMERGENCY, event.mode)
        assertTrue(event.fullScreenRemote)
    }

    @Test
    fun `an unknown call mode falls back to normal rather than throwing`() {
        val event = Protocol.parse(
            """{"type":"incoming-call","callId":"c","from":"a","fromName":"b","mode":"something-new"}"""
        ) as ServerEvent.IncomingCall
        assertEquals(CallMode.NORMAL, event.mode)
        assertFalse(event.fullScreenRemote)
    }

    @Test
    fun `parses the peer list of a group call`() {
        val event = Protocol.parse(
            """
            {"type":"call-peers","callId":"c","mode":"normal","fullScreenRemote":false,"peers":[
              {"peerId":"tv-1","peerName":"Living Room TV","role":"tv","offer":false},
              {"peerId":"phone-b","peerName":"Sam","role":"phone","offer":true},
              {"peerName":"no id, so skipped"}
            ]}
            """.trimIndent()
        ) as ServerEvent.CallPeers

        assertEquals("c", event.callId)
        assertEquals(CallMode.NORMAL, event.mode)
        assertEquals(listOf("tv-1", "phone-b"), event.peers.map { it.peerId })
        assertEquals("tv", event.peers[0].role)
        assertFalse(event.peers[0].offer)
        assertTrue(event.peers[1].offer)
        assertEquals("Sam", event.peers[1].peerName)
    }

    @Test
    fun `parses a relayed signal without touching its payload`() {
        val event = Protocol.parse(
            """{"type":"signal","callId":"c","from":"tv-1","payload":{"kind":"offer","sdp":"v=0 …"}}"""
        ) as ServerEvent.Signal
        assertEquals("tv-1", event.from)
        assertEquals("offer", event.payload.optString("kind"))
        assertEquals("v=0 …", event.payload.optString("sdp"))
    }

    @Test
    fun `parses presence`() {
        val event = Protocol.parse(
            """
            {"type":"presence","devices":[
              {"deviceId":"tv-1","role":"tv","displayName":"TV","online":true},
              {"deviceId":"p-1","role":"phone","displayName":"Alex","online":false}
            ],"call":{"callId":"c-9","active":true,"members":["tv-1","p-2"]}}
            """.trimIndent()
        ) as ServerEvent.Presence

        assertEquals(2, event.devices.size)
        assertTrue(event.devices[0].online)
        assertFalse(event.devices[1].online)
        assertEquals("phone", event.devices[1].role)
        assertEquals("c-9", event.call?.callId)
        assertTrue(event.call!!.active)
        assertEquals(listOf("tv-1", "p-2"), event.call!!.memberIds)
    }

    @Test
    fun `presence with no call going on`() {
        val event = Protocol.parse("""{"type":"presence","devices":[],"call":null}""") as ServerEvent.Presence
        assertNull(event.call)
    }

    @Test
    fun `parses endings`() {
        assertEquals(
            "timeout",
            (Protocol.parse("""{"type":"call-cancelled","callId":"c","reason":"timeout"}""")
                as ServerEvent.CallCancelled).reason
        )
        assertNotNull(Protocol.parse("""{"type":"call-timeout","callId":"c"}"""))
        assertEquals(
            "busy",
            (Protocol.parse("""{"type":"error","code":"busy","message":"in a call"}""")
                as ServerEvent.Error).code
        )
    }

    @Test
    fun `unknown and malformed messages are ignored, not fatal`() {
        // A newer server must never be able to crash an older app.
        assertNull(Protocol.parse("""{"type":"some-future-message","x":1}"""))
        assertNull(Protocol.parse("not json at all"))
        assertNull(Protocol.parse(""))
    }

    @Test
    fun `builds a register message the server will accept`() {
        val json = JSONObject(
            Protocol.register(
                deviceId = "d-1",
                role = "tv",
                displayName = "Living Room TV",
                secret = "s".repeat(64),
                fcmToken = null
            )
        )
        assertEquals("register", json.getString("type"))
        assertEquals("tv", json.getString("role"))
        assertEquals("Living Room TV", json.getString("displayName"))
        assertFalse("no token should be sent when we have none", json.has("fcmToken"))
    }

    @Test
    fun `builds a signal envelope addressed to the peer`() {
        val json = JSONObject(
            Protocol.signal("c-1", "phone-a", JSONObject().put("kind", "ice").put("candidate", "x"))
        )
        assertEquals("signal", json.getString("type"))
        assertEquals("c-1", json.getString("callId"))
        assertEquals("phone-a", json.getString("to"))
        assertEquals("ice", json.getJSONObject("payload").getString("kind"))
    }

    @Test
    fun `builds a call request carrying the mode`() {
        val json = JSONObject(Protocol.callRequest(CallMode.EMERGENCY, fullScreenRemote = true))
        assertEquals("call-request", json.getString("type"))
        assertEquals("emergency", json.getString("mode"))
        assertTrue(json.getBoolean("fullScreenRemote"))
    }
}
