package com.kalathil.intercom.core

import android.content.Context
import android.media.AudioManager
import android.util.Log
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Builds the audio device module, with echo handling chosen per device.
 *
 * This is where the TV's worst problem lives. The webcam's microphone and the
 * TV's speakers are separate pieces of hardware with independent clocks, which
 * is the hardest case for any echo canceller: the platform's hardware AEC is
 * built for a phone holding its own mic and earpiece, and when handed this
 * arrangement it either does nothing or actively chews up speech.
 *
 * So on the TV we turn the hardware canceller off and let WebRTC's own AEC3 run,
 * because AEC3 at least receives both the captured and the played-out signal and
 * can estimate the delay between them. It is not as good as a USB speakerphone
 * with hardware cancellation - nothing software-only is - but it is the best
 * available with the hardware you have.
 */
// Public, not internal: the tv and phone modules put the device into call mode
// through this, and internal visibility stops at the :core module boundary.
object AudioModuleFactory {

    private const val TAG = "AudioModule"

    fun create(context: Context, preferSoftwareEchoCancellation: Boolean): AudioDeviceModule {
        val hwAecAvailable = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val hwNsAvailable = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()

        val useHardwareAec = hwAecAvailable && !preferSoftwareEchoCancellation
        val useHardwareNs = hwNsAvailable && !preferSoftwareEchoCancellation

        Log.i(
            TAG,
            "hardware AEC available=$hwAecAvailable using=$useHardwareAec; " +
                "hardware NS available=$hwNsAvailable using=$useHardwareNs"
        )

        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(useHardwareAec)
            .setUseHardwareNoiseSuppressor(useHardwareNs)
            // VOICE_COMMUNICATION is what tells the platform this is a call, which
            // engages whatever routing and gain behaviour it reserves for calls.
            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                // Block bodies, not `= Log.e(...)`: Log.e returns Int and these
                // callbacks return Unit, which Kotlin rejects as an override.
                override fun onWebRtcAudioRecordInitError(msg: String) {
                    Log.e(TAG, "record init error: $msg")
                }

                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    msg: String
                ) {
                    Log.e(TAG, "record start error ($code): $msg")
                }

                override fun onWebRtcAudioRecordError(msg: String) {
                    Log.e(TAG, "record error: $msg")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(msg: String) {
                    Log.e(TAG, "track init error: $msg")
                }

                override fun onWebRtcAudioTrackStartError(
                    code: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    msg: String
                ) {
                    Log.e(TAG, "track start error ($code): $msg")
                }

                override fun onWebRtcAudioTrackError(msg: String) {
                    Log.e(TAG, "track error: $msg")
                }
            })
            .createAudioDeviceModule()
    }

    /**
     * Put the device into call mode. Without this the TV plays call audio through
     * the media stream, where the platform will not apply any call-specific
     * processing and volume behaves oddly.
     */
    fun enterCallMode(context: Context, speakerphone: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerphone
    }

    fun leaveCallMode(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
    }
}
