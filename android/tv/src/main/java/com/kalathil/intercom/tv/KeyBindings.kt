package com.kalathil.intercom.tv

import android.content.Context
import android.view.KeyEvent

/**
 * Which physical keys do what.
 *
 * A USB macro pad can send almost any keycode and the packaging never says
 * which, so these are learned rather than guessed: Setup has a "press the
 * button now" mode that captures whatever arrives. The defaults are the
 * four coloured keys found on most TV remotes.
 */
class KeyBindings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("intercom_keys", Context.MODE_PRIVATE)

    /** The big one. Grandpa presses this to call everybody. */
    var callKey: Int
        get() = prefs.getInt(KEY_CALL, KeyEvent.KEYCODE_PROG_RED)
        set(value) = prefs.edit().putInt(KEY_CALL, value).apply()

    /** Answers a ringing call. */
    var answerKey: Int
        get() = prefs.getInt(KEY_ANSWER, KeyEvent.KEYCODE_PROG_GREEN)
        set(value) = prefs.edit().putInt(KEY_ANSWER, value).apply()

    /** Declines, or ends the call. */
    var hangupKey: Int
        get() = prefs.getInt(KEY_HANGUP, KeyEvent.KEYCODE_PROG_BLUE)
        set(value) = prefs.edit().putInt(KEY_HANGUP, value).apply()

    /**
     * Hard camera off/on. His switch, not ours - see the privacy note in
     * TvCallActivity. Held for a second so it cannot be hit by accident.
     */
    var cameraToggleKey: Int
        get() = prefs.getInt(KEY_CAMERA, KeyEvent.KEYCODE_PROG_YELLOW)
        set(value) = prefs.edit().putInt(KEY_CAMERA, value).apply()

    fun describe(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_PROG_RED -> "Red button"
        KeyEvent.KEYCODE_PROG_GREEN -> "Green button"
        KeyEvent.KEYCODE_PROG_YELLOW -> "Yellow button"
        KeyEvent.KEYCODE_PROG_BLUE -> "Blue button"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
    }

    /**
     * Keys we must never bind, because swallowing them would make the TV
     * unusable for everything else.
     */
    fun isReserved(keyCode: Int): Boolean = keyCode in RESERVED

    private companion object {
        const val KEY_CALL = "call"
        const val KEY_ANSWER = "answer"
        const val KEY_HANGUP = "hangup"
        const val KEY_CAMERA = "camera"

        val RESERVED = setOf(
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE
        )
    }
}
