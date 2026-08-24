package com.casatrack.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

object ActivityRecognitionSupport {
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1001, Intent(context, ActivityTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val types = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.ON_FOOT
        )
        val transitions = buildList {
            for (t in types) {
                add(ActivityTransition.Builder().setActivityType(t).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build())
                add(ActivityTransition.Builder().setActivityType(t).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build())
            }
        }
        ActivityRecognition.getClient(context).requestActivityTransitionUpdates(
            ActivityTransitionRequest(transitions), pendingIntent(context)
        )
    }

    fun unregister(context: Context) {
        runCatching { ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context)) }
    }

    fun map(event: ActivityTransitionEvent): ActivityMode = when (event.activityType) {
        DetectedActivity.IN_VEHICLE -> ActivityMode.DRIVING
        DetectedActivity.ON_BICYCLE -> ActivityMode.CYCLING
        DetectedActivity.RUNNING -> ActivityMode.RUNNING
        DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> ActivityMode.WALKING
        DetectedActivity.STILL -> ActivityMode.STILL
        else -> ActivityMode.UNKNOWN
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents.forEach { event ->
            if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                TrackingService.dispatchActivity(context, ActivityRecognitionSupport.map(event))
            }
        }
    }
}
