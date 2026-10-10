package com.kalathil.intercom.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Catches the call button no matter what is on screen.
 *
 * This service exists for one reason: on Android TV, key events are delivered
 * only to the foreground app. If grandpa is watching YouTube when he presses the
 * button, YouTube gets the keypress and our app never hears about it - which
 * would make the whole device useless exactly when it is needed. An
 * accessibility service with `flagRequestFilterKeyEvents` is the only supported
 * way to see keys globally, so the button works mid-programme.
 *
 * It has to be enabled once, by hand, in Settings -> Accessibility. Setup walks
 * you through it and warns loudly if it is off.
 */
class ButtonAccessibilityService : AccessibilityService() {

    private lateinit var bindings: KeyBindings
    private val handler = Handler(Looper.getMainLooper())

    /** Set while the camera key is held down, to require a deliberate long press. */
    private var cameraKeyDownAt = 0L
    private var cameraToggleFired = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        bindings = KeyBindings(this)
        running = true
        Log.i(TAG, "button service connected")
        // Starting the service here means a TV that reboots comes back ready
        // without anyone opening the app.
        IntercomService.start(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Settings is learning which key is which; let every key reach it.
        if (passKeysThrough) return false

        // Pass everything we do not own straight through, untouched.
        if (code != bindings.callKey &&
            code != bindings.answerKey &&
            code != bindings.hangupKey &&
            code != bindings.cameraToggleKey
        ) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (code == bindings.cameraToggleKey) {
                    if (cameraKeyDownAt == 0L) {
                        cameraKeyDownAt = System.currentTimeMillis()
                        cameraToggleFired = false
                        handler.postDelayed(cameraLongPress, CAMERA_HOLD_MS)
                    }
                    return true
                }
                // Repeats from a held-down key would otherwise place several calls.
                if (event.repeatCount == 0) handlePress(code)
                return true
            }

            KeyEvent.ACTION_UP -> {
                if (code == bindings.cameraToggleKey) {
                    handler.removeCallbacks(cameraLongPress)
                    if (!cameraToggleFired) {
                        Log.d(TAG, "camera key released too early, ignoring")
                    }
                    cameraKeyDownAt = 0L
                }
                return true
            }
        }
        return true
    }

    private val cameraLongPress = Runnable {
        cameraToggleFired = true
        Log.i(TAG, "camera toggle held long enough")
        send(IntercomService.ACTION_TOGGLE_CAMERA)
    }

    private fun handlePress(code: Int) {
        when (code) {
            bindings.callKey -> {
                Log.i(TAG, "call button pressed")
                send(IntercomService.ACTION_PRESS_CALL)
            }

            bindings.answerKey -> {
                Log.i(TAG, "answer button pressed")
                send(IntercomService.ACTION_PRESS_ANSWER)
            }

            bindings.hangupKey -> {
                Log.i(TAG, "hangup button pressed")
                send(IntercomService.ACTION_PRESS_HANGUP)
            }
        }
    }

    private fun send(action: String) {
        val intent = Intent(this, IntercomService::class.java).setAction(action)
        runCatching { startForegroundService(intent) }
            .onFailure { Log.e(TAG, "could not reach the intercom service", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ButtonService"

        /** Deliberate hold, so a stray press never turns the camera back on. */
        private const val CAMERA_HOLD_MS = 1_000L

        /**
         * Whether the service is currently running, so Setup can tell the user
         * plainly that the button will not work until they enable it.
         */
        @Volatile
        var running: Boolean = false
            private set

        /** Set by Settings while it learns the buttons. */
        @Volatile
        var passKeysThrough: Boolean = false
    }
}
