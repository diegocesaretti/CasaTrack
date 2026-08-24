package com.casatrack.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var api: EditText
    private lateinit var device: EditText
    private lateinit var person: EditText
    private lateinit var token: EditText
    private lateinit var ssid: EditText
    private lateinit var bssids: EditText
    private lateinit var anchor: TextView
    private lateinit var status: TextView
    private lateinit var startStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(buildUi())
        load()
        refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, dp(12), 0, dp(3)) }
        fun field(hint: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun button(t: String, click: () -> Unit) = Button(this).apply { text = t; setOnClickListener { click() } }

        root.addView(TextView(this).apply { text = "CasaTrack · prototipo"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Ubicación adaptativa con Wi‑Fi ancla, actividad y Cloudflare"; textSize = 14f })

        root.addView(label("Worker URL")); api = field("https://xxxxx.workers.dev"); root.addView(api)
        root.addView(label("ID del dispositivo")); device = field("diego-s23"); root.addView(device)
        root.addView(label("Persona")); person = field("Diego"); root.addView(person)
        root.addView(label("Token del dispositivo")); token = field("token", true); root.addView(token)
        root.addView(button("Guardar configuración") { save(); refreshStatus() })

        root.addView(label("Wi‑Fi ancla")); ssid = field("SSID"); root.addView(ssid)
        root.addView(label("BSSID(s), opcional — separados por coma")); bssids = field("AA:BB:CC:DD:EE:FF"); root.addView(bssids)
        anchor = TextView(this); root.addView(anchor)
        root.addView(button("Capturar Wi‑Fi actual + ubicación ancla") { save(); captureAnchor() })

        root.addView(button("Conceder permisos necesarios") { requestCorePermissions() })
        root.addView(button("Abrir ajustes de batería") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        status = TextView(this).apply { setPadding(0, dp(16), 0, dp(8)) }; root.addView(status)
        startStop = button("Iniciar seguimiento") { toggleTracking() }; root.addView(startStop)

        return ScrollView(this).apply { addView(root) }
    }

    private fun load() {
        api.setText(prefs.apiUrl); device.setText(prefs.deviceId); person.setText(prefs.personName); token.setText(prefs.deviceToken)
        ssid.setText(prefs.anchorSsid); bssids.setText(prefs.anchorBssids)
        refreshAnchorText()
    }

    private fun save() {
        prefs.apiUrl = api.text.toString(); prefs.deviceId = device.text.toString(); prefs.personName = person.text.toString(); prefs.deviceToken = token.text.toString()
        prefs.anchorSsid = ssid.text.toString(); prefs.anchorBssids = bssids.text.toString()
        toast("Guardado")
    }

    private fun captureAnchor() {
        if (!hasFine()) { requestCorePermissions(); return }
        val w = WifiProbe.current(this)
        if (w.ssid.isNullOrBlank()) { toast("No pude leer el Wi‑Fi actual. Verificá Ubicación y permisos."); return }
        val req = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setDurationMillis(15_000).build()
        LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(req, null).addOnCompleteListener { task ->
            val loc: Location? = task.result
            if (loc == null) { toast("No conseguí una ubicación fresca"); return@addOnCompleteListener }
            prefs.anchorSsid = w.ssid
            prefs.anchorBssids = w.bssid ?: ""
            prefs.anchorLat = loc.latitude; prefs.anchorLon = loc.longitude
            ssid.setText(prefs.anchorSsid); bssids.setText(prefs.anchorBssids)
            refreshAnchorText(); toast("Ancla guardada")
            if (prefs.trackingEnabled) { GeofenceSupport.unregister(this); GeofenceSupport.registerHome(this, prefs) }
        }
    }

    private fun toggleTracking() {
        save()
        if (prefs.trackingEnabled) {
            prefs.trackingEnabled = false
            TrackingService.stop(this)
        } else {
            if (!prefs.configured()) { toast("Falta URL, device ID o token"); return }
            if (!hasFine()) { requestCorePermissions(); return }
            prefs.trackingEnabled = true
            TrackingService.start(this)
        }
        refreshStatus()
    }

    private fun requestCorePermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) wanted += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 90)
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Después elegí Ubicación → Permitir todo el tiempo en la pantalla de permisos de la app.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else if (Build.VERSION.SDK_INT == 29 && hasFine()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 91)
        }
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshAnchorText() {
        anchor.text = if (prefs.hasAnchor()) "Ancla: ${"%.6f".format(prefs.anchorLat)}, ${"%.6f".format(prefs.anchorLon)} · radio ${prefs.anchorRadiusM.toInt()} m" else "Sin ubicación ancla"
    }

    private fun refreshStatus() {
        status.text = "Seguimiento: ${if (prefs.trackingEnabled) "ACTIVO" else "detenido"}\nActividad: ${prefs.activity}\nÚltimo fix: ${if (prefs.lastFixAt > 0) java.util.Date(prefs.lastFixAt) else "—"}"
        startStop.text = if (prefs.trackingEnabled) "Detener seguimiento" else "Iniciar seguimiento"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
