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
        private const val EXTRA_ENTERING = "entering"
        private const val EXTRA_INSIDE = "inside"
        private const val NOTIFICATION_ID = 4201
        private const val CHANNEL_ID = "tracking"

        private const val HEARTBEAT_MS = 15 * 60_000L
        private const val STALE_DRIVING_MS = 10 * 60_000L
        private const val DRIVING_PROBE_WINDOW_MS = 90_000L
        private const val DRIVING_RECHECK_MS = 30_000L
        private const val DRIVING_STATIONARY_TIMEOUT_MS = 2 * 60_000L

        fun start(context: Context) {
            val i = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, TrackingService::class.java))

        fun dispatchActivity(context: Context, mode: ActivityMode, entering: Boolean) {
            val i = Intent(context, TrackingService::class.java)
                .setAction(ACTION_ACTIVITY)
                .putExtra(EXTRA_ACTIVITY, mode.wire)
                .putExtra(EXTRA_ENTERING, entering)
            runCatching { context.startService(i) }
        }

        fun dispatchGeofence(context: Context, inside: Boolean) {
            val i = Intent(context, TrackingService::class.java)
                .setAction(ACTION_GEOFENCE)
                .putExtra(EXTRA_INSIDE, inside)
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
    private var wifiHold = false
    private var anchorWifi = false
    private var lastLocation: Location? = null
    private var lastUploadAt = 0L
    private var geofenceInside: Boolean? = null

    private var drivingCandidateUntil = 0L
    private var drivingProbeAttempts = 0
    private var drivingProbeSawFix = false
    private var drivingStationarySince = 0L
    private var pendingNetworkReason = "network_change"

    private val heartbeat = object : Runnable {
        override fun run() {
            publishCurrent("heartbeat", force = true)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val delayedNetworkRefresh = Runnable {
        val reason = pendingNetworkReason
        refreshNetwork(reason)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocation(it, "gps") }
        }
    }

    private val networkCallback: ConnectivityManager.NetworkCallback by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                override fun onAvailable(network: Network) = scheduleNetworkRefresh("network_available")
                override fun onLost(network: Network) = scheduleNetworkRefresh("network_lost")
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    scheduleNetworkRefresh("network_capabilities")
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = scheduleNetworkRefresh("network_available")
                override fun onLost(network: Network) = scheduleNetworkRefresh("network_lost")
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    scheduleNetworkRefresh("network_capabilities")
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
        if (mode == ActivityMode.DRIVING && System.currentTimeMillis() - prefs.activityChangedAt > STALE_DRIVING_MS) {
            setModeDirect(ActivityMode.UNKNOWN, 50)
        }

        lastLocation = if (prefs.lastLat.isFinite() && prefs.lastLon.isFinite()) {
            Location("stored").apply {
                latitude = prefs.lastLat
                longitude = prefs.lastLon
                accuracy = prefs.lastAccuracy
            }
        } else null

        createChannel()
        startForeground(NOTIFICATION_ID, notification("Inicializando"))
        ActivityRecognitionSupport.register(this)
        GeofenceSupport.registerHome(this, prefs)
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
        refreshNetwork("startup")
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVITY -> {
                val candidate = ActivityMode.fromWire(intent.getStringExtra(EXTRA_ACTIVITY))
                val entering = intent.getBooleanExtra(EXTRA_ENTERING, true)
                if (entering) handleActivityEnter(candidate) else handleActivityExit(candidate)
            }

            ACTION_GEOFENCE -> {
                geofenceInside = intent.getBooleanExtra(EXTRA_INSIDE, false)
                refreshNetwork("geofence")
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

    private fun handleActivityEnter(candidate: ActivityMode) {
        if (candidate == ActivityMode.DRIVING) {
            beginDrivingProbe()
            return
        }

        cancelDrivingProbe()
        val wasHolding = wifiHold
        setActivity(candidate, 95)
        refreshNetwork("activity_enter")

        // STILL intentionally has no active location subscription. A subsequent
        // motion transition, geofence event or network change wakes tracking again.
        if (candidate != ActivityMode.STILL && !wifiHold && !wasHolding) {
            requestImmediateFix("activity_enter")
        }
    }

    private fun handleActivityExit(candidate: ActivityMode) {
        if (candidate == ActivityMode.DRIVING) cancelDrivingProbe()
        if (mode != candidate) return

        setModeDirect(ActivityMode.UNKNOWN, 58)
        drivingStationarySince = 0L
        refreshNetwork("activity_exit_${candidate.wire}")
        if (!wifiHold) requestImmediateFix("activity_exit_${candidate.wire}")
    }

    private fun beginDrivingProbe() {
        if (mode == ActivityMode.DRIVING) return
        drivingCandidateUntil = System.currentTimeMillis() + DRIVING_PROBE_WINDOW_MS
        drivingProbeAttempts = 0
        drivingProbeSawFix = false
        runDrivingProbe()
    }

    private fun runDrivingProbe() {
        if (mode == ActivityMode.DRIVING || System.currentTimeMillis() > drivingCandidateUntil) {
            cancelDrivingProbe()
            return
        }

        drivingProbeAttempts += 1
        val previous = lastLocation
        requestOneFreshLocation("driving_probe") { fresh ->
            if (fresh != null) drivingProbeSawFix = true

            if (fresh != null && hasDrivingEvidence(previous, fresh)) {
                setModeDirect(ActivityMode.DRIVING, 96)
                drivingStationarySince = 0L
                cancelDrivingProbe()
                onLocation(fresh, "driving_confirmed")
                refreshNetwork("driving_confirmed")
                return@requestOneFreshLocation
            }

            if (drivingProbeAttempts < 2 && System.currentTimeMillis() < drivingCandidateUntil) {
                handler.postDelayed({ runDrivingProbe() }, DRIVING_RECHECK_MS)
            } else {
                val noFixFallback = !wifi.connected && !drivingProbeSawFix
                cancelDrivingProbe()
                if (noFixFallback) {
                    // Activity Recognition is still useful evidence. If GPS could not
                    // obtain either probe and there is no Wi-Fi, prefer tracking over
                    // silently missing a trip; the stationary watchdog will back off.
                    setModeDirect(ActivityMode.DRIVING, 76)
                    refreshNetwork("driving_probe_no_fix")
                } else if (!wifi.connected && mode == ActivityMode.STILL) {
                    setModeDirect(ActivityMode.UNKNOWN, 55)
                    configureLocationUpdates()
                }
                updateNotification()
            }
        }
        updateNotification()
    }

    private fun cancelDrivingProbe() {
        drivingCandidateUntil = 0L
        drivingProbeAttempts = 0
        drivingProbeSawFix = false
    }

    private fun hasDrivingEvidence(previous: Location?, fresh: Location): Boolean {
        if (fresh.hasSpeed() && fresh.speed >= 3f) return true
        if (previous == null) return false

        val distance = previous.distanceTo(fresh)
        val derivedSpeed = derivedSpeedMps(previous, fresh)
        if (derivedSpeed >= 3f) return true

        // Stored/wifi-hold points may not have a meaningful timestamp. A large
        // displacement from that held point is still strong evidence of travel.
        return distance >= 80f && (previous.time <= 0L || fresh.time <= previous.time || fresh.time - previous.time <= 3 * 60_000L)
    }

    private fun scheduleNetworkRefresh(reason: String) {
        pendingNetworkReason = reason
        handler.removeCallbacks(delayedNetworkRefresh)
        handler.postDelayed(delayedNetworkRefresh, 1_250L)
    }

    private fun refreshNetwork(reason: String) {
        val previousHold = wifiHold
        val previousWifi = wifi

        wifi = WifiProbe.current(this)

        val ssidMatch = wifi.connected && wifi.ssid != null && wifi.ssid == prefs.anchorSsid
        anchorWifi = ssidMatch && prefs.bssidMatches(wifi.bssid) && prefs.hasAnchor() && geofenceInside != false

        // Any Wi-Fi means low mobility unless vehicle movement has actually been
        // confirmed. An IN_VEHICLE transition alone no longer defeats Wi-Fi hold.
        wifiHold = wifi.connected && mode != ActivityMode.DRIVING

        val networkChanged = previousWifi.connected != wifi.connected ||
            previousWifi.ssid != wifi.ssid ||
            previousWifi.bssid != wifi.bssid

        when {
            wifiHold && (!previousHold || networkChanged) -> {
                if (reason == "driving_stationary" && lastLocation != null) {
                    fused.removeLocationUpdates(locationCallback)
                    publishCurrent("wifi_hold", force = true)
                    updateNotification()
                } else {
                    requestOneFreshLocation("gps_before_wifi_sleep") { fresh ->
                        if (fresh != null) onLocation(fresh, "gps_before_wifi_sleep")
                        if (wifiHold) {
                            fused.removeLocationUpdates(locationCallback)
                            publishCurrent("wifi_hold", force = true)
                            updateNotification()
                        }
                    }
                }
            }

            previousHold && !wifiHold -> {
                configureLocationUpdates()
                when (reason) {
                    "driving_confirmed", "location_activity_fusion" -> publishCurrent("driving_resume", force = true)
                    else -> {
                        val source = if (wifi.connected && mode == ActivityMode.DRIVING) {
                            "wifi_driving_override"
                        } else {
                            "wifi_disconnect"
                        }
                        requestImmediateFix(source)
                    }
                }
            }

            else -> {
                configureLocationUpdates()
                if (reason != "startup") publishCurrent("network_change", force = true)
            }
        }
    }

    private fun configureLocationUpdates() {
        if (!hasLocationPermission()) return
        fused.removeLocationUpdates(locationCallback)

        // Stillness and Wi-Fi are event-driven states: keep Activity Recognition,
        // geofences, network callbacks and heartbeat, but do not actively run GPS.
        if (wifiHold || mode == ActivityMode.STILL) {
            updateNotification()
            return
        }

        val (interval, minInterval, distance, priority) = when (mode) {
            ActivityMode.DRIVING -> Profile(45_000L, 20_000L, 125f, Priority.PRIORITY_HIGH_ACCURACY)
            ActivityMode.CYCLING -> Profile(60_000L, 30_000L, 100f, Priority.PRIORITY_HIGH_ACCURACY)
            ActivityMode.RUNNING -> Profile(90_000L, 45_000L, 50f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.WALKING -> Profile(2 * 60_000L, 60_000L, 75f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.UNKNOWN -> Profile(5 * 60_000L, 2 * 60_000L, 100f, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            ActivityMode.STILL -> return
        }

        val request = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .setMinUpdateDistanceMeters(distance)
            .setMaxUpdateDelayMillis(interval * 3)
            .build()

        runCatching { fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper()) }
        updateNotification()
    }

    private data class Profile(
        val interval: Long,
        val minInterval: Long,
        val distance: Float,
        val priority: Int
    )

    private fun setActivity(candidate: ActivityMode, confidence: Int) {
        val fusedResult = ActivityFusion.fuse(candidate, lastLocation, mode)
        setModeDirect(fusedResult.mode, maxOf(confidence, fusedResult.confidence).coerceAtMost(99))
    }

    private fun setModeDirect(newMode: ActivityMode, confidence: Int) {
        modeConfidence = confidence.coerceIn(0, 99)
        if (newMode != mode) {
            mode = newMode
            prefs.activity = mode.wire
            prefs.activityChangedAt = System.currentTimeMillis()
        } else {
            prefs.activity = mode.wire
        }
        updateNotification()
    }

    private fun onLocation(location: Location, source: String) {
        val previousLocation = lastLocation
        lastLocation = location
        prefs.lastLat = location.latitude
        prefs.lastLon = location.longitude
        prefs.lastAccuracy = location.accuracy
        prefs.lastFixAt = System.currentTimeMillis()

        val previousMode = mode
        val fusedResult = ActivityFusion.fuse(mode, location, mode)
        mode = fusedResult.mode
        modeConfidence = maxOf(modeConfidence, fusedResult.confidence).coerceAtMost(99)
        prefs.activity = mode.wire

        if (mode == ActivityMode.DRIVING) {
            val speed = effectiveSpeedMps(previousLocation, location)
            if (speed >= 2f) {
                drivingStationarySince = 0L
            } else if (speed >= 0f && location.accuracy <= 100f) {
                if (drivingStationarySince == 0L) drivingStationarySince = System.currentTimeMillis()
                if (System.currentTimeMillis() - drivingStationarySince >= DRIVING_STATIONARY_TIMEOUT_MS) {
                    setModeDirect(ActivityMode.STILL, 88)
                    drivingStationarySince = 0L
                    refreshNetwork("driving_stationary")
                }
            }
        } else {
            drivingStationarySince = 0L
        }

        if (mode != previousMode) {
            prefs.activityChangedAt = System.currentTimeMillis()
            refreshNetwork("location_activity_fusion")
        }

        publishLocation(location, source, force = false)
        updateNotification()
    }

    private fun effectiveSpeedMps(previous: Location?, current: Location): Float {
        if (current.hasSpeed()) return current.speed
        return derivedSpeedMps(previous, current)
    }

    private fun derivedSpeedMps(previous: Location?, current: Location): Float {
        if (previous == null || previous.time <= 0L || current.time <= previous.time) return -1f
        val seconds = (current.time - previous.time) / 1000f
        if (seconds <= 0f || seconds > 10 * 60f) return -1f
        return previous.distanceTo(current) / seconds
    }

    private fun requestImmediateFix(source: String) =
        requestOneFreshLocation(source) { it?.let { loc -> onLocation(loc, source) } }

    private fun requestOneFreshLocation(source: String, cb: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            cb(null)
            return
        }

        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(15_000L)
            .setDurationMillis(12_000L)
            .build()

        runCatching {
            fused.getCurrentLocation(req, null).addOnCompleteListener { task -> cb(task.result) }
        }.onFailure { cb(null) }
    }

    private fun publishCurrent(source: String, force: Boolean) {
        if (wifiHold) {
            if (anchorWifi && prefs.hasAnchor()) {
                publishAnchor("wifi_anchor", force)
            } else {
                lastLocation?.let { publishLocation(it, "wifi_hold", force) }
            }
        } else {
            lastLocation?.let { publishLocation(it, source, force) }
        }
    }

    private fun publishAnchor(source: String, force: Boolean) {
        val anchor = Location("wifi_anchor").apply {
            latitude = prefs.anchorLat
            longitude = prefs.anchorLon
            accuracy = prefs.anchorRadiusM
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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Seguimiento de ubicación", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(detail: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("CasaTrack activo")
        .setContentText(detail)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                5,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification() {
        if (!::cm.isInitialized) return
        val wifiName = wifi.ssid ?: "Wi-Fi"
        val probingDriving = drivingCandidateUntil > System.currentTimeMillis()
        val detail = when {
            probingDriving -> "Verificando movimiento · GPS breve"
            wifiHold -> "$wifiName: GPS en reposo · ${mode.wire}"
            mode == ActivityMode.STILL -> "still · GPS en reposo"
            wifi.connected && mode == ActivityMode.DRIVING -> "$wifiName · driving confirmado"
            else -> "${mode.wire} · seguimiento adaptativo"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail))
    }
}
