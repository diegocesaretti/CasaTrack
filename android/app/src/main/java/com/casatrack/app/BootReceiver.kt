package com.casatrack.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (AppPrefs(context).trackingEnabled) {
            runCatching { TrackingService.start(context) }
        }
    }
}
