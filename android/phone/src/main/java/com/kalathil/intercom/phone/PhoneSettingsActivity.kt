package com.kalathil.intercom.phone

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.kalathil.intercom.core.Config

/** Connection details: entered once, then kept out of the way of the home screen. */
class PhoneSettingsActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var serverUrl: EditText
    private lateinit var homeSecret: EditText
    private lateinit var displayName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = Config(this)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        serverUrl = findViewById(R.id.serverUrl)
        homeSecret = findViewById(R.id.homeSecret)
        displayName = findViewById(R.id.displayName)

        serverUrl.setText(config.serverUrl)
        homeSecret.setText(config.homeSecret)
        displayName.setText(config.displayName)

        findViewById<View>(R.id.save).setOnClickListener { save() }
    }

    private fun save() {
        val url = serverUrl.text.toString().trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            toast("The address must start with wss://")
            return
        }
        val secret = homeSecret.text.toString().trim()
        if (secret.length < 20) {
            toast("That secret looks too short")
            return
        }
        val name = displayName.text.toString().trim()
        if (name.isBlank()) {
            toast("Enter your name - it shows on his TV")
            return
        }

        config.serverUrl = url
        config.homeSecret = secret
        config.displayName = name
        PhoneIntercomService.send(this, PhoneIntercomService.ACTION_SETTINGS_CHANGED)
        toast(getString(R.string.saved))
        finish()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
