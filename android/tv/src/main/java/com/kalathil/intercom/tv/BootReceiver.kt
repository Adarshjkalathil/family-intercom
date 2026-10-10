package com.kalathil.intercom.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kalathil.intercom.core.Config

/**
 * Brings the intercom back after a power cut.
 *
 * Without this, a brief outage leaves the TV looking normal while the call
 * button quietly does nothing until somebody opens the app - which, for the
 * person this is built for, means never.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (!Config(context).isConfigured) {
            Log.w("BootReceiver", "booted but not configured, nothing to start")
            return
        }
        Log.i("BootReceiver", "booted, starting intercom service")
        IntercomService.start(context)
    }
}
