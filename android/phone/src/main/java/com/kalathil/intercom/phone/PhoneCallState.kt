package com.kalathil.intercom.phone

import com.kalathil.intercom.core.CallMode

/** What the phone's call screen should be showing. */
sealed interface PhoneCallState {
    data object Idle : PhoneCallState

    /** Asked to call; waiting for the connection or the server to confirm. */
    data object Placing : PhoneCallState

    data class Dialling(val callId: String, val ringTimeoutMs: Long) : PhoneCallState

    data class Ringing(
        val callId: String,
        val fromName: String,
        val mode: CallMode
    ) : PhoneCallState

    data class Connecting(val callId: String, val peerName: String) : PhoneCallState

    data class InCall(val callId: String, val peerName: String) : PhoneCallState

    data class Ended(val reason: String) : PhoneCallState
}
