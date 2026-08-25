package com.casatrack.app

import android.location.Location
import org.json.JSONObject
import java.util.UUID

enum class ActivityMode(val wire: String) {
    STILL("still"), WALKING("walking"), RUNNING("running"), CYCLING("cycling"), DRIVING("driving"), UNKNOWN("unknown");
    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: UNKNOWN
    }
}

data class WifiState(val connected: Boolean, val ssid: String?, val bssid: String?)

data class Telemetry(
    val eventId: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val personName: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    val activity: ActivityMode,
    val activityConfidence: Int,
    val locationSource: String,
    val wifiSsid: String?,
    val wifiBssid: String?,
    val batteryPct: Int,
    val charging: Boolean,
    val clientTimeMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("device_id", deviceId)
        put("person_name", personName)
        put("latitude", lat)
        put("longitude", lon)
        put("accuracy_m", accuracyM.toDouble())
        if (speedMps != null) put("speed_mps", speedMps.toDouble()) else put("speed_mps", JSONObject.NULL)
        put("activity", activity.wire)
        put("activity_confidence", activityConfidence)
        put("location_source", locationSource)
        put("wifi_ssid", wifiSsid ?: JSONObject.NULL)
        put("wifi_bssid", wifiBssid ?: JSONObject.NULL)
        put("battery_pct", batteryPct)
        put("charging", charging)
        put("client_time_ms", clientTimeMs)
    }
}

object ActivityFusion {
    data class Result(val mode: ActivityMode, val confidence: Int)

    fun fuse(transition: ActivityMode, location: Location?, previous: ActivityMode): Result {
        val speed = location?.takeIf { it.hasSpeed() }?.speed ?: -1f

        // A real high-speed fix is strong evidence regardless of the transition API.
        if (speed >= 11f) return Result(ActivityMode.DRIVING, 94)

        // DRIVING is confirmed by TrackingService before it reaches this point.
        // Once confirmed, do not drop it on a single noisy/zero-speed fix; the
        // service has a stationary watchdog and EXIT transitions for that.
        if (transition == ActivityMode.DRIVING) return Result(ActivityMode.DRIVING, 94)

        if (transition == ActivityMode.CYCLING) return Result(ActivityMode.CYCLING, 90)
        if (transition == ActivityMode.RUNNING) return Result(ActivityMode.RUNNING, 90)
        if (transition == ActivityMode.WALKING) return Result(ActivityMode.WALKING, 90)
        if (transition == ActivityMode.STILL) return Result(ActivityMode.STILL, 90)

        if (speed >= 3f) return Result(ActivityMode.CYCLING, 68)
        if (speed >= 0.8f) return Result(ActivityMode.WALKING, 62)
        return Result(previous.takeIf { it != ActivityMode.UNKNOWN } ?: ActivityMode.UNKNOWN, 50)
    }
}
