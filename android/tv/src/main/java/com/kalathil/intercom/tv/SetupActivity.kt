package com.kalathil.intercom.tv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kalathil.intercom.core.Config
import com.kalathil.intercom.core.SignallingClient
import kotlinx.coroutines.launch

/**
 * The TV app's front screen. You use it once; grandpa never does.
 *
 * It shows only what matters at a glance - connected or not, and whether the
 * accessibility service is on (which silently breaks the call button while
 * everything else still looks fine) - plus a test call. Server address, home
 * secret and remote buttons are on [TvSettingsActivity].
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var config: Config

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var setupHint: TextView
    private lateinit var cameraStatus: TextView
    private lateinit var accessibilityWarning: TextView
    private lateinit var openAccessibility: View
    private lateinit var testCallButton: View
    private lateinit var openSettings: View

    /**
     * The TV app never used to ask for these, so the microphone recorded silence
     * and Android 14 refused to start the service. Asked here, on the screen the
     * installer uses, so the prompt never appears in front of grandpa.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == false) {
            toast("Microphone not allowed, so grandpa will not be heard. Allow it in the TV's app settings.")
        }
        // Let the running service claim microphone/camera use now that it may.
        IntercomService.send(this, IntercomService.ACTION_RECONNECT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        config = Config(this)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        setupHint = findViewById(R.id.setupHint)
        cameraStatus = findViewById(R.id.cameraStatus)
        accessibilityWarning = findViewById(R.id.accessibilityWarning)
        openAccessibility = findViewById(R.id.openAccessibility)
        testCallButton = findViewById(R.id.testCall)
        openSettings = findViewById(R.id.openSettings)

        testCallButton.setOnClickListener { testCall() }
        openSettings.setOnClickListener { startActivity(Intent(this, TvSettingsActivity::class.java)) }
        openAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                toast("Could not open settings — find Accessibility in the TV's own settings")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    IntercomService.connectionState.collect { renderStatus(it) }
                }
                launch {
                    IntercomService.cameraStatus.collect { renderFooter() }
                }
            }
        }

        requestMissingPermissions()
        IntercomService.start(this)
    }

    override fun onResume() {
        super.onResume()
        renderAccessibilityState()
        val configured = config.isConfigured
        setupHint.visibility = if (configured) View.GONE else View.VISIBLE
        testCallButton.isEnabled = configured
        renderStatus(IntercomService.connectionState.value)
        // Put the remote's focus where the next step is.
        when {
            !configured -> openSettings.requestFocus()
            openAccessibility.visibility == View.VISIBLE -> openAccessibility.requestFocus()
            else -> testCallButton.requestFocus()
        }
    }

    // -----------------------------------------------------------------------

    private fun testCall() {
        if (!config.isConfigured) {
            toast("Open Settings and save the server details first")
            return
        }
        if (IntercomService.connectionState.value != SignallingClient.State.CONNECTED) {
            toast("Not connected to the server yet")
            return
        }
        IntercomService.send(this, IntercomService.ACTION_PRESS_CALL)
    }

    private fun renderStatus(state: SignallingClient.State) {
        val (text, colour) = when {
            !config.isConfigured -> getString(R.string.status_not_configured) to R.color.amber
            state == SignallingClient.State.CONNECTED -> getString(R.string.status_connected) to R.color.brand_light
            state == SignallingClient.State.CONNECTING -> getString(R.string.status_connecting) to R.color.amber
            state == SignallingClient.State.UNAUTHORISED -> getString(R.string.status_unauthorised) to R.color.danger
            else -> getString(R.string.status_offline) to R.color.danger
        }
        status.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colour))
    }

    /**
     * The accessibility service is the single most likely thing to be silently
     * off, and when it is, the call button does nothing while the rest of the
     * app looks perfectly healthy. So it gets a red banner, not a subtle hint.
     */
    private fun renderAccessibilityState() {
        val enabled = isAccessibilityServiceEnabled()
        accessibilityWarning.visibility = if (enabled) View.GONE else View.VISIBLE
        openAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
        renderFooter()
    }

    /** One quiet line: is the button service up, and what did the camera last say. */
    private fun renderFooter() {
        val camera = IntercomService.cameraStatus.value
        cameraStatus.text = listOfNotNull(
            getString(R.string.accessibility_ok).takeIf { isAccessibilityServiceEnabled() },
            camera.ifBlank { null }
        ).joinToString("  ·  ")
        cameraStatus.visibility = if (cameraStatus.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ButtonAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // The USB webcam does not need it, but Android 14 does for a
            // camera-type foreground service.
            add(Manifest.permission.CAMERA)
            // The call screen is raised through a full-screen notification.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
