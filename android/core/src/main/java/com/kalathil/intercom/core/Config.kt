package com.kalathil.intercom.core

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Everything the app needs to find and authenticate to the server.
 *
 * Deliberately entered once through a setup screen rather than baked into the
 * APK, so the same build works for every family member's phone and the secret
 * is not sitting in a file in a git repository.
 */
class Config(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** e.g. wss://yourname.duckdns.org/ws */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    /** The shared family secret printed by deploy/setup.sh. */
    var homeSecret: String
        get() = prefs.getString(KEY_HOME_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_SECRET, value.trim()).apply()

    /** Shown to whoever is being called, so "Alex" beats a UUID. */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    /**
     * Whether the camera is allowed to open at all. Grandpa can toggle this from
     * the macro pad; when it is off, even an emergency check-in gets audio only.
     * Him having a real switch matters more than the feature does.
     */
    var cameraEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_ENABLED, value).apply()

    /**
     * Stable per-install identity. Generated once and never changed, so the
     * server can recognise a device across reinstalls of the same app data and
     * replace its old socket instead of accumulating ghosts.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    /** True once the setup screen has enough to connect. */
    val isConfigured: Boolean
        get() = serverUrl.startsWith("ws") && homeSecret.length >= 20 && displayName.isNotBlank()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS = "intercom_config"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_HOME_SECRET = "home_secret"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_CAMERA_ENABLED = "camera_enabled"
    }
}
