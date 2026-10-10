package com.kalathil.intercom.tv

import com.kalathil.intercom.core.CallMode

/**
 * What the TV screen should be showing.
 *
 * Kept as one sealed type owned by IntercomService so the activity is purely a
 * view of it. The activity can be destroyed and recreated (the TV sleeping, the
 * launcher reclaiming it) without the call itself being affected.
 */
sealed interface CallUiState {

    data object Idle : CallUiState

    /** Grandpa pressed the button; waiting for the server to start ringing. */
    data object Placing : CallUiState

    /** Grandpa pressed the button; we are ringing everybody. */
    data class Dialling(
        val callId: String,
        val ringTimeoutMs: Long,
        val startedAt: Long = System.currentTimeMillis()
    ) : CallUiState

    /** Somebody is calling and we are waiting for grandpa to accept. */
    data class Ringing(
        val callId: String,
        val fromName: String,
        val mode: CallMode,
        val fullScreenRemote: Boolean
    ) : CallUiState

    /** Accepted, media still being negotiated. */
    data class Connecting(
        val callId: String,
        val peerName: String,
        val autoAnswered: Boolean,
        val fullScreenRemote: Boolean
    ) : CallUiState

    data class InCall(
        val callId: String,
        val peerName: String,
        /**
         * True when this call opened the camera without grandpa doing anything.
         * The UI must announce it - chime plus a border for the whole duration.
         * Silently watching someone in their own home is not a thing this app does.
         */
        val autoAnswered: Boolean,
        val fullScreenRemote: Boolean,
        val cameraOn: Boolean
    ) : CallUiState

    /** Briefly shown so he knows what happened, then back to Idle. */
    data class Ended(val reason: EndReason, val peerName: String? = null) : CallUiState
}

enum class EndReason {
    /** Nobody picked up within 45 seconds. */
    NOBODY_ANSWERED,

    /** Someone called the TV and it rang out unanswered. */
    MISSED,
    HUNG_UP,
    ANSWERED_ELSEWHERE,
    CONNECTION_LOST,

    /** No phone has ever connected to the server, so there is nobody to ring. */
    NO_PHONES,
    FAILED
}
