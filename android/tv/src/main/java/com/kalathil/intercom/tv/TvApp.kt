package com.kalathil.intercom.tv

import android.app.Application
import android.util.Log
import com.kalathil.intercom.core.Config

/**
 * Starts the intercom service as soon as the process exists, so the TV is ready
 * to ring without anyone having opened the app.
 */
class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = Config(this)
        if (config.isConfigured) {
            Log.i("TvApp", "configured, starting intercom service")
            IntercomService.start(this)
        } else {
            Log.w("TvApp", "not configured - open Family Intercom on the TV to set it up")
        }
    }
}
