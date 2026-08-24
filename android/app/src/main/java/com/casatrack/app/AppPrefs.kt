package com.casatrack.app

import android.content.Context

class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("casatrack", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = p.getString("api_url", "") ?: ""
        set(v) = p.edit().putString("api_url", v.trim().trimEnd('/')).apply()
    var deviceId: String
        get() = p.getString("device_id", "") ?: ""
        set(v) = p.edit().putString("device_id", v.trim()).apply()
    var personName: String
        get() = p.getString("person_name", "") ?: ""
        set(v) = p.edit().putString("person_name", v.trim()).apply()
    var deviceToken: String
        get() = p.getString("device_token", "") ?: ""
        set(v) = p.edit().putString("device_token", v.trim()).apply()

    var anchorSsid: String
        get() = p.getString("anchor_ssid", "") ?: ""
        set(v) = p.edit().putString("anchor_ssid", v.trim()).apply()
    var anchorBssids: String
        get() = p.getString("anchor_bssids", "") ?: ""
        set(v) = p.edit().putString("anchor_bssids", v.trim()).apply()
    var anchorLat: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("anchor_lat", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("anchor_lat", java.lang.Double.doubleToRawLongBits(v)).apply()
    var anchorLon: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("anchor_lon", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("anchor_lon", java.lang.Double.doubleToRawLongBits(v)).apply()
    var anchorRadiusM: Float
        get() = p.getFloat("anchor_radius", 150f)
        set(v) = p.edit().putFloat("anchor_radius", v.coerceIn(50f, 1000f)).apply()

    var trackingEnabled: Boolean
        get() = p.getBoolean("tracking_enabled", false)
        set(v) = p.edit().putBoolean("tracking_enabled", v).apply()

    var activity: String
        get() = p.getString("activity", ActivityMode.UNKNOWN.wire) ?: ActivityMode.UNKNOWN.wire
        set(v) = p.edit().putString("activity", v).apply()
    var activityChangedAt: Long
        get() = p.getLong("activity_changed_at", 0L)
        set(v) = p.edit().putLong("activity_changed_at", v).apply()

    var lastLat: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("last_lat", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("last_lat", java.lang.Double.doubleToRawLongBits(v)).apply()
    var lastLon: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("last_lon", java.lang.Double.doubleToLongBits(Double.NaN)))
        set(v) = p.edit().putLong("last_lon", java.lang.Double.doubleToRawLongBits(v)).apply()
    var lastAccuracy: Float
        get() = p.getFloat("last_accuracy", 9999f)
        set(v) = p.edit().putFloat("last_accuracy", v).apply()
    var lastFixAt: Long
        get() = p.getLong("last_fix_at", 0L)
        set(v) = p.edit().putLong("last_fix_at", v).apply()

    fun configured(): Boolean = apiUrl.startsWith("https://") && deviceId.isNotBlank() && deviceToken.isNotBlank()
    fun hasAnchor(): Boolean = anchorSsid.isNotBlank() && anchorLat.isFinite() && anchorLon.isFinite()

    fun bssidMatches(candidate: String?): Boolean {
        val configured = anchorBssids.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (configured.isEmpty()) return true
        val c = candidate?.lowercase() ?: return false
        return configured.any { it == c }
    }
}
