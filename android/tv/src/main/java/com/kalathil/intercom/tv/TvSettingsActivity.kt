package com.kalathil.intercom.tv

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kalathil.intercom.core.Config

/**
 * Settings: the server connection, and teaching the app which physical keys the
 * macro pad or remote sends. Kept off the front screen so nothing sensitive or
 * fiddly sits in plain view.
 */
class TvSettingsActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var bindings: KeyBindings

    private lateinit var serverUrl: EditText
    private lateinit var homeSecret: EditText
    private lateinit var displayName: EditText
    private lateinit var keyBindingsText: TextView

    /**
     * Non-null while waiting for the user to press a key to bind. The button
     * service stands aside meanwhile, or pressing a key that is already bound
     * would place a call instead of being learned.
     */
    private var learning: LearnStep? = null
        set(value) {
            field = value
            ButtonAccessibilityService.passKeysThrough = value != null
        }
    private var learnDialog: AlertDialog? = null

    private enum class LearnStep(val labelRes: Int) {
        CALL(R.string.setup_learn_call),
        ANSWER(R.string.setup_learn_answer),
        HANGUP(R.string.setup_learn_hangup),
        CAMERA(R.string.setup_learn_camera);

        fun next(): LearnStep? = entries.getOrNull(ordinal + 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_settings)

        config = Config(this)
        bindings = KeyBindings(this)

        serverUrl = findViewById(R.id.serverUrl)
        homeSecret = findViewById(R.id.homeSecret)
        displayName = findViewById(R.id.displayName)
        keyBindingsText = findViewById(R.id.keyBindings)

        serverUrl.setText(config.serverUrl)
        homeSecret.setText(config.homeSecret)
        displayName.setText(config.displayName.ifBlank { "Living room TV" })

        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        findViewById<View>(R.id.learnKeys).setOnClickListener { startLearning() }

        // Back is first in the layout, so a remote would otherwise land on the
        // one button that throws the trip away. Start on the first field.
        serverUrl.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        renderKeyBindings()
    }

    private fun save() {
        val url = serverUrl.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast("The server address must start with wss://")
            return
        }
        val secret = homeSecret.text.toString().trim()
        if (secret.length < 20) {
            toast("That home secret looks too short — copy the whole secret")
            return
        }
        val name = displayName.text.toString().trim()
        if (name.isBlank()) {
            toast("Give this TV a name")
            return
        }

        config.serverUrl = url
        config.homeSecret = secret
        config.displayName = name

        toast("Saved")
        IntercomService.send(this, IntercomService.ACTION_SETTINGS_CHANGED)
    }

    private fun renderKeyBindings() {
        keyBindingsText.text = buildString {
            append("Call: ").append(bindings.describe(bindings.callKey)).append('\n')
            append("Answer: ").append(bindings.describe(bindings.answerKey)).append('\n')
            append("Hang up: ").append(bindings.describe(bindings.hangupKey)).append('\n')
            append("Camera on/off (hold 1s): ").append(bindings.describe(bindings.cameraToggleKey))
        }
    }

    // -----------------------------------------------------------------------
    // Learning which key the macro pad sends
    // -----------------------------------------------------------------------

    private fun startLearning() {
        learning = LearnStep.CALL
        showLearnPrompt()
    }

    private fun showLearnPrompt() {
        val step = learning ?: return
        learnDialog?.dismiss()
        learnDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_learn_prompt, getString(step.labelRes)))
            .setMessage("Press the key on the macro pad or remote now.\n\nBack cancels.")
            .setCancelable(true)
            .setOnCancelListener {
                learning = null
                renderKeyBindings()
            }
            // The dialog has its own window, so keys go to it - never to this
            // activity's onKeyDown.
            .setOnKeyListener { _, keyCode, event ->
                when {
                    keyCode == KeyEvent.KEYCODE_BACK -> false // let it cancel
                    event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 -> true
                    else -> onLearnKey(keyCode)
                }
            }
            .show()
    }

    /**
     * Key capture for the learn flow. A macro pad is just a keyboard, so
     * whatever it sends arrives here and we record it rather than guessing from
     * the packaging, which never says.
     */
    private fun onLearnKey(keyCode: Int): Boolean {
        val step = learning ?: return false

        if (bindings.isReserved(keyCode)) {
            toast("${bindings.describe(keyCode)} is needed by the TV — pick another key")
            return true
        }
        // One key doing two jobs would make the later job unreachable.
        val taken = LearnStep.entries.take(step.ordinal).firstOrNull { keyFor(it) == keyCode }
        if (taken != null) {
            toast("${bindings.describe(keyCode)} is already ${getString(taken.labelRes)} — pick another key")
            return true
        }

        when (step) {
            LearnStep.CALL -> bindings.callKey = keyCode
            LearnStep.ANSWER -> bindings.answerKey = keyCode
            LearnStep.HANGUP -> bindings.hangupKey = keyCode
            LearnStep.CAMERA -> bindings.cameraToggleKey = keyCode
        }
        toast("${getString(step.labelRes)} = ${bindings.describe(keyCode)}")

        learning = step.next()
        if (learning == null) {
            learnDialog?.dismiss()
            renderKeyBindings()
            toast("All four buttons set")
        } else {
            showLearnPrompt()
        }
        return true
    }

    private fun keyFor(step: LearnStep): Int = when (step) {
        LearnStep.CALL -> bindings.callKey
        LearnStep.ANSWER -> bindings.answerKey
        LearnStep.HANGUP -> bindings.hangupKey
        LearnStep.CAMERA -> bindings.cameraToggleKey
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onPause() {
        // Never leave the call button switched off behind us.
        if (learning != null) {
            learning = null
            learnDialog?.dismiss()
            renderKeyBindings()
        }
        super.onPause()
    }

    override fun onDestroy() {
        learnDialog?.dismiss()
        super.onDestroy()
    }
}
