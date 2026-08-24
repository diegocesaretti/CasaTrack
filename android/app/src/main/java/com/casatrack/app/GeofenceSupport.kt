package com.casatrack.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

object GeofenceSupport {
    private const val ID = "trusted_wifi_anchor"

    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1002, Intent(context, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun registerHome(context: Context, prefs: AppPrefs) {
        if (!prefs.hasAnchor()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val fence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(prefs.anchorLat, prefs.anchorLon, prefs.anchorRadiusM)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setLoiteringDelay(0)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(fence)
            .build()
        runCatching { LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context)) }
    }

    fun unregister(context: Context) {
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)) }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> TrackingService.dispatchGeofence(context, false)
            Geofence.GEOFENCE_TRANSITION_ENTER -> TrackingService.dispatchGeofence(context, true)
        }
    }
}
