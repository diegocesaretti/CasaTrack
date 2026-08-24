package com.casatrack.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class TrackingService : Service() {
    companion object {
        private const val ACTION_ACTIVITY = "casatrack.activity"
        private const val ACTION_GEOFENCE = "casatrack.geofence"
        private const val EXTRA_ACTIVITY = "activity"
        private const val EXTRA_INSIDE = "inside"
        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "tracking"

        fun start(context: Context) {
            val i = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
        fun stop(context: Context) = context.stopService(Intent(context, TrackingService::class.java))
        fun dispatchActivity(context: Context, mode: ActivityMode) {
            val i = Intent(context, TrackingService::class.java).setAction(ACTION_ACTIVITY).putExtra(EXTRA_ACTIVITY, mode.wire)
            runCatching { context.startService(i) }
        }
        fun dispatchGeofence(context: Context, inside: Boolean) {
            val i = Intent(context, TrackingService::class.java).setAction(ACTION_GEOFENCE).putExtra(EXTRA_INSIDE, inside)
            runCatching { context.startService(i) }
        }
    }

    private lateinit var prefs: AppPrefs
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var cm: ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var mode = ActivityMode.UNKNOWN
    private var modeConfidence = 50
    private var wifi = WifiState(false, null, null)
    private var trustedWifi = false
    private var lastLocation: Location? = null
    private var lastUploadAt = 0L
    private var geofenceInside: Boolean? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            publishCurrent("heartbeat", force = true)
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it, "gps") }
        }
    }

    private val networkCallback: ConnectivityManager.NetworkCallback by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) { handler.post { refreshNetwork("network_available") } }
                override fun onLost(network: Network) { handler.post { refreshNetwork("network_lost") } }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    handler.post { refreshNetwork("network_capabilities") }
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { refreshNetwork("network_available") } }
                override fun onLost(network: Network) { handler.post { refreshNetwork("network_lost") } }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    handler.post { refreshNetwork("network_capabilities") }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        mode = ActivityMode.fromWire(prefs.activity)
        lastLocation = if (prefs.lastLat.isFinite() && prefs.lastLon.isFinite()) Location("stored").apply {
            latitude = prefs.lastLat; longitude = prefs.lastLon; accuracy = prefs.lastAccuracy
        } else null
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Inicializando"))
        ActivityRecognitionSupport.register(this)
        GeofenceSupport.registerHome(this, prefs)
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
        refreshNetwork("startup")
        handler.postDelayed(heartbeat, 15 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVITY -> {
                val candidate = ActivityMode.fromWire(intent.getStringExtra(EXTRA_ACTIVITY))
                setActivity(candidate, 95, "activity_transition")
                if (!trustedWifi) requestImmediateFix("activity_transition")
            }
            ACTION_GEOFENCE -> {
                geofenceInside = intent.getBooleanExtra(EXTRA_INSIDE, false)
                if (geofenceInside == false && trustedWifi) {
                    trustedWifi = false
                    configureLocationUpdates()
                    requestImmediateFix("geofence_exit")
                } else publishCurrent("geofence", force = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fused.removeLocationUpdates(locationCallback)
        ActivityRecognitionSupport.unregister(this)
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshNetwork(reason: String) {
        val previous = trustedWifi
        wifi = WifiProbe.current(this)
        val ssidMatch = wifi.connected && wifi.ssid != null && wifi.ssid == prefs.anchorSsid
        trustedWifi = ssidMatch && prefs.bssidMatches(wifi.bssid) && prefs.hasAnchor() && geofenceInside != false

        if (!previous && trustedWifi) {
            requestOneFreshLocation("gps_before_wifi_sleep") { fresh ->
                if (fresh != null) onLocation(fresh, "gps_before_wifi_sleep")
                if (trustedWifi) {
                    fused.removeLocationUpdates(locationCallback)
                    publishAnchor("wifi_anchor", force = true)
                    updateNotification()
                }
            }
        } else if (previous && !trustedWifi) {
            configureLocationUpdates()
            requestImmediateFix("wifi_disconnect")
        } else {
            configureLocationUpdates()
            if (reason != "startup") publishCurrent("network_change", force = true)
        }
    }

    private fun configureLocationUpdates() {
        if (!hasLocationPermission()) return
        fused.removeLocationUpdates(locationCallback)
        if (trustedWifi) return

        val (interval, minInterval, distance, priority) = when (mode) {
            ActivityMode.DRIVING -> Profile(20_000L, 10_000L, 50f, Priority.PRIORITY_HIGH_ACCURACY)
            ActivityMode.CYCLING -> Profile(30_000L, 15_000L, 25f, Priority.PRIORITY_HIGH_ACCURACY)
            ActivityMode.RUNNING -> Profile(45_000L, 20_000L, 20f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.WALKING -> Profile(60_000L, 30_000L, 25f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.STILL -> Profile(5 * 60_000L, 2 * 60_000L, 100f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.UNKNOWN -> Profile(2 * 60_000L, 60_000L, 50f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        }
        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(distance)
            .setMaxUpdateDelayMillis(interval * 2)
            .build()
        runCatching { fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper()) }
        updateNotification()
    }

    private data class Profile(val interval: Long, val minInterval: Long, val distance: Float, val priority: Int)

    private fun setActivity(candidate: ActivityMode, confidence: Int, source: String) {
        val fusedResult = ActivityFusion.fuse(candidate, lastLocation, mode)
        val newMode = fusedResult.mode
        modeConfidence = maxOf(confidence, fusedResult.confidence).coerceAtMost(99)
        if (newMode != mode) {
            mode = newMode
            prefs.activity = mode.wire
            prefs.activityChangedAt = System.currentTimeMillis()
            configureLocationUpdates()
            publishCurrent(source, force = true)
        }
        updateNotification()
    }

    private fun onLocation(location: Location, source: String) {
        lastLocation = location
        prefs.lastLat = location.latitude
        prefs.lastLon = location.longitude
        prefs.lastAccuracy = location.accuracy
        prefs.lastFixAt = System.currentTimeMillis()
        val fusedResult = ActivityFusion.fuse(mode, location, mode)
        mode = fusedResult.mode
        modeConfidence = maxOf(modeConfidence, fusedResult.confidence).coerceAtMost(99)
        prefs.activity = mode.wire
        publishLocation(location, source, force = false)
        updateNotification()
    }

    private fun requestImmediateFix(source: String) = requestOneFreshLocation(source) { it?.let { loc -> onLocation(loc, source) } }

    private fun requestOneFreshLocation(source: String, cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) { cb(null); return }
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000L)
            .setDurationMillis(15_000L)
            .build()
        runCatching {
            fused.getCurrentLocation(req, null).addOnCompleteListener { task -> cb(task.result) }
        }.onFailure { cb(null) }
    }

    private fun publishCurrent(source: String, force: Boolean) {
        if (trustedWifi && prefs.hasAnchor()) publishAnchor("wifi_anchor", force)
        else lastLocation?.let { publishLocation(it, source, force) }
    }

    private fun publishAnchor(source: String, force: Boolean) {
        val anchor = Location("wifi_anchor").apply {
            latitude = prefs.anchorLat; longitude = prefs.anchorLon; accuracy = prefs.anchorRadiusM
        }
        publishLocation(anchor, source, force)
    }

    private fun publishLocation(location: Location, source: String, force: Boolean) {
        if (!prefs.configured()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastUploadAt < 8_000L) return
        lastUploadAt = now
        val battery = PhoneState.battery(this)
        val telemetry = Telemetry(
            deviceId = prefs.deviceId,
            personName = prefs.personName,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy.coerceAtLeast(1f),
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            activity = mode,
            activityConfidence = modeConfidence,
            locationSource = source,
            wifiSsid = wifi.ssid,
            wifiBssid = wifi.bssid,
            batteryPct = battery.pct,
            charging = battery.charging
        )
        ApiClient.sendOrQueue(this, telemetry)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Seguimiento de ubicación", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(detail: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("CasaTrack activo")
        .setContentText(detail)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification() {
        val detail = if (trustedWifi) "${prefs.anchorSsid}: GPS en reposo · ${mode.wire}" else "${mode.wire} · seguimiento adaptativo"
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification(detail))
    }
}
