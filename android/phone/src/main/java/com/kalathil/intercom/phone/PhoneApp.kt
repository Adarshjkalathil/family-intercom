package com.kalathil.intercom.phone

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.kalathil.intercom.core.Config

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = Config(this)

        // Fetch the push token early so the very first registration carries it;
        // otherwise the first call after install would not wake a locked phone.
        // Fetched even before setup: a phone set up later in this same session
        // would otherwise register without one and never ring when closed.
        if (BuildConfig.HAS_FIREBASE) {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    Log.i(TAG, "FCM token acquired")
                    config.fcmToken = token
                    if (!config.isConfigured) return@addOnSuccessListener
                    // Android may refuse to start the service while the app is in
                    // the background. The token is saved either way and is sent on
                    // the next connection, so a refusal must not crash the app.
                    runCatching { PhoneIntercomService.send(this, PhoneIntercomService.ACTION_CONNECT) }
                        .onFailure { Log.w(TAG, "service not started now; token goes out on next connect", it) }
                }
                .addOnFailureListener { Log.e(TAG, "could not get an FCM token", it) }
        } else {
            Log.w(TAG, "built without google-services.json - no push, so no ringing when closed")
        }
    }

    private companion object { const val TAG = "PhoneApp" }
}
