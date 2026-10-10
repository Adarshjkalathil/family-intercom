package com.kalathil.intercom.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.kalathil.intercom.core.CallSummary
import com.kalathil.intercom.core.Config
import com.kalathil.intercom.core.DevicePresence
import com.kalathil.intercom.core.SignallingClient
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Home screen: whether the TV is there, and the three things you can start.
 * Connection details live on [PhoneSettingsActivity], out of the way.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: Config
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var setupCard: View
    private lateinit var tvCard: View
    private lateinit var actions: View
    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDot: View
    private lateinit var callTv: MaterialButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val missing = granted.filterValues { !it }.keys
        if (missing.isNotEmpty()) {
            Toast.makeText(this, getString(R.string.permissions_needed), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = Config(this)
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        setupCard = findViewById(R.id.setupCard)
        tvCard = findViewById(R.id.tvCard)
        actions = findViewById(R.id.actions)
        tvName = findViewById(R.id.tvName)
        tvStatus = findViewById(R.id.tvStatus)
        tvDot = findViewById(R.id.tvDot)
        callTv = findViewById(R.id.callTv)

        findViewById<View>(R.id.pushWarning).visibility =
            if (BuildConfig.HAS_FIREBASE) View.GONE else View.VISIBLE

        findViewById<View>(R.id.settingsButton).setOnClickListener { openSettings() }
        findViewById<View>(R.id.openSetup).setOnClickListener { openSettings() }

        // Also joins a call the family is already in: the server sees to that.
        callTv.setOnClickListener {
            withPermissions { PhoneIntercomService.send(this, PhoneIntercomService.ACTION_CALL_TV) }
        }
        findViewById<View>(R.id.showMeOnTv).setOnClickListener {
            withPermissions { PhoneIntercomService.send(this, PhoneIntercomService.ACTION_SHOW_ME_ON_TV) }
        }
        findViewById<View>(R.id.checkIn).setOnClickListener {
            withPermissions { PhoneIntercomService.send(this, PhoneIntercomService.ACTION_CHECK_IN) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PhoneIntercomService.connectionState.collect { renderStatus(it) } }
                launch {
                    combine(PhoneIntercomService.presence, PhoneIntercomService.activeCall) { devices, call ->
                        devices to call
                    }.collect { (devices, call) -> renderTv(devices, call) }
                }
            }
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        val configured = config.isConfigured
        setupCard.visibility = if (configured) View.GONE else View.VISIBLE
        tvCard.visibility = if (configured) View.VISIBLE else View.GONE
        actions.visibility = if (configured) View.VISIBLE else View.GONE
        renderStatus(PhoneIntercomService.connectionState.value)
        // Coming back from Settings with new details connects straight away.
        if (configured) PhoneIntercomService.send(this, PhoneIntercomService.ACTION_CONNECT)
    }

    private fun openSettings() {
        startActivity(Intent(this, PhoneSettingsActivity::class.java))
    }

    private fun renderStatus(state: SignallingClient.State) {
        val (text, colour) = when {
            !config.isConfigured -> getString(R.string.status_not_configured) to R.color.danger
            state == SignallingClient.State.CONNECTED -> getString(R.string.status_connected) to R.color.brand
            state == SignallingClient.State.CONNECTING -> getString(R.string.status_connecting) to R.color.text_secondary
            state == SignallingClient.State.UNAUTHORISED -> getString(R.string.status_unauthorised) to R.color.danger
            else -> getString(R.string.status_offline) to R.color.danger
        }
        status.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colour))
    }

    /**
     * The TV card, like a contact row: its name, and online or offline - or who
     * is talking to grandpa right now, with the call button offering to join.
     */
    private fun renderTv(devices: List<DevicePresence>, call: CallSummary?) {
        val tv = devices.firstOrNull { it.role == "tv" && it.online } ?: devices.firstOrNull { it.role == "tv" }
        tvName.text = tv?.displayName?.ifBlank { null } ?: getString(R.string.grandpas_tv)

        val others = call?.takeIf { it.active && config.deviceId !in it.memberIds }
            ?.memberIds
            ?.mapNotNull { id -> devices.firstOrNull { it.deviceId == id && it.role == "phone" }?.displayName }
            .orEmpty()
        callTv.setText(if (others.isNotEmpty()) R.string.join_call else R.string.call_grandpa)
        tvStatus.text = when {
            others.isNotEmpty() -> getString(R.string.tv_in_call, others.joinToString(", "))
            tv == null -> getString(R.string.tv_not_setup)
            tv.online -> getString(R.string.tv_online)
            else -> getString(R.string.tv_offline)
        }
        val dot = if (tv?.online == true) R.color.brand else R.color.text_secondary
        tvDot.backgroundTintList = ColorStateList.valueOf(getColor(dot))
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    /** Calls need camera and mic; asking at the moment of use explains why. */
    private fun withPermissions(action: () -> Unit) {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else permissionLauncher.launch(missing.toTypedArray())
    }
}
