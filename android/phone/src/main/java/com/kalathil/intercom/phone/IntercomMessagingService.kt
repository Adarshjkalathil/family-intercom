package com.kalathil.intercom.phone

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kalathil.intercom.core.CallMode
import com.kalathil.intercom.core.Config

/**
 * Receives the wake-up push.
 *
 * The server sends data-only messages on purpose: a `notification` payload would
 * be drawn by the system while the app stays asleep, which is useless for a call.
 * Data-only guarantees this code runs, so we can raise a real full-screen ringing
 * screen over the lock screen.
 */
class IntercomMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "incoming_call" -> {
                val callId = data["callId"] ?: return
                val fromName = data["fromName"] ?: "TV"
                val mode = CallMode.from(data["mode"])
                Log.i(TAG, "push: incoming call $callId from $fromName")
                startService { PhoneIntercomService.onPushReceived(this, callId, fromName, mode) }
            }

            "call_cancelled" -> {
                Log.i(TAG, "push: call cancelled (${data["reason"]})")
                // Local dismissal only. Sending a decline or hangup from here
                // used to end the call for whichever phone had answered.
                startService {
                    PhoneIntercomService.send(
                        this,
                        PhoneIntercomService.ACTION_REMOTE_CANCELLED,
                        data["callId"],
                        data["reason"]
                    )
                }
            }

            else -> Log.d(TAG, "ignoring push of type ${data["type"]}")
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated")
        val config = Config(this)
        config.fcmToken = token
        // Tell the server immediately; a stale token means a silent phone. If
        // Android will not start the service now, the next connection sends it.
        if (config.isConfigured) {
            startService { PhoneIntercomService.send(this, PhoneIntercomService.ACTION_CONNECT) }
        }
    }

    /**
     * Android can refuse a background service start even for a high-priority
     * push (when it has been demoted, or for a token refresh). That must be a
     * logged miss, not a crash of the push receiver.
     */
    private inline fun startService(start: () -> Unit) {
        runCatching { start() }.onFailure { Log.w(TAG, "could not start the call service", it) }
    }

    private companion object { const val TAG = "IntercomFcm" }
}
