package com.casatrack.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: AppPrefs

    private var api: EditText? = null
    private var person: EditText? = null
    private var deviceLabel: EditText? = null
    private var invitationStatus: TextView? = null

    private lateinit var identity: TextView
    private lateinit var anchor: TextView
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var claimAdmin: Button
    private lateinit var inviteDevice: Button
    private lateinit var manageDevices: Button

    private var pendingInvite: String? = null
    private var canClaimAdmin = false

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) {
            toast("Escaneo cancelado")
        } else {
            handleInvitationQr(content)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(buildUi())
        refreshStatus()
        if (prefs.configured()) refreshIdentityFromServer()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }

        fun heading(text: String, size: Float = 18f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(16), 0, dp(6))
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun button(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { click() }
        }

        root.addView(TextView(this).apply { text = "CasaTrack"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Ubicación familiar privada y eficiente"
            textSize = 14f
        })

        identity = TextView(this).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(identity)

        if (!prefs.configured()) {
            root.addView(heading("Agregar este teléfono"))
            root.addView(TextView(this).apply {
                text = "En otro teléfono CasaTrack tocá “Invitar otro teléfono” y escaneá el QR. No necesitás copiar IDs ni tokens."
            })

            root.addView(TextView(this).apply { text = "Persona"; setPadding(0, dp(12), 0, 0) })
            person = field("Nombre").also {
                it.setText(prefs.personName)
                root.addView(it)
            }

            root.addView(TextView(this).apply { text = "Nombre del teléfono"; setPadding(0, dp(12), 0, 0) })
            deviceLabel = field("Mi teléfono").also {
                it.setText(prefs.deviceLabel)
                root.addView(it)
            }

            root.addView(button("Escanear invitación") { scanInvitation() })
            invitationStatus = TextView(this).apply {
                text = "Todavía no hay una invitación escaneada"
                setPadding(0, dp(6), 0, dp(6))
            }
            root.addView(invitationStatus)
            root.addView(button("Registrar este teléfono") { enrollThisPhone() })

            root.addView(heading("Avanzado", 15f))
            root.addView(TextView(this).apply {
                text = "La URL se completa automáticamente al escanear. Sólo hace falta escribirla si estás recuperando una instalación."
                textSize = 12f
            })
            api = field("https://xxxxx.workers.dev").also {
                it.setText(prefs.apiUrl)
                root.addView(it)
            }
        } else {
            root.addView(heading("Seguimiento"))
            status = TextView(this).apply { setPadding(0, dp(4), 0, dp(8)) }
            root.addView(status)

            startStop = button("Iniciar seguimiento") { toggleTracking() }
            root.addView(startStop)
            root.addView(button("Conceder permisos necesarios") { requestCorePermissions() })
            root.addView(button("Abrir ajustes de batería") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })

            root.addView(heading("Familia"))
            claimAdmin = button("Activar administración familiar") { claimAdministration() }.apply {
                visibility = View.GONE
            }
            root.addView(claimAdmin)

            inviteDevice = button("Invitar otro teléfono") { createInvitation() }.apply {
                visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
            }
            root.addView(inviteDevice)

            manageDevices = button("Administrar dispositivos") { showFamilyDevices() }.apply {
                visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
            }
            root.addView(manageDevices)

            root.addView(heading("Ubicación de casa (opcional)", 15f))
            root.addView(TextView(this).apply {
                text = "CasaTrack ya duerme el GPS con cualquier Wi‑Fi. Guardar la Wi‑Fi de casa sólo mejora la posición fija cuando estás en casa."
                textSize = 12f
            })
            anchor = TextView(this)
            root.addView(anchor)
            root.addView(button("Guardar Wi‑Fi actual como casa") { captureAnchor() })
            refreshAnchorText()

            root.addView(heading("Información", 15f))
            root.addView(TextView(this).apply {
                text = "Worker: ${prefs.apiUrl}\nID interno: ${prefs.deviceId}\nVersión: ${BuildConfig.VERSION_NAME}"
                textSize = 12f
            })
        }

        if (!::status.isInitialized) {
            status = TextView(this)
            startStop = Button(this)
        }
        if (!::claimAdmin.isInitialized) claimAdmin = Button(this)
        if (!::inviteDevice.isInitialized) inviteDevice = Button(this)
        if (!::manageDevices.isInitialized) manageDevices = Button(this)
        if (!::anchor.isInitialized) anchor = TextView(this)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshIdentityText(serverLabel: String? = null) {
        identity.text = if (prefs.configured()) {
            val admin = if (prefs.isAdmin) " · Administrador" else ""
            "${prefs.personName} · ${serverLabel?.takeIf { it.isNotBlank() } ?: prefs.deviceLabel}$admin"
        } else {
            "Este teléfono todavía no está registrado"
        }
    }

    private fun refreshIdentityFromServer() {
        refreshIdentityText()
        Thread {
            runCatching { FamilyClient.me(prefs) }
                .onSuccess { me ->
                    runOnUiThread {
                        prefs.isAdmin = me.optBoolean("is_admin", false)
                        canClaimAdmin = me.optBoolean("can_claim_admin", false)
                        val serverLabel = me.optString("label")
                        if (serverLabel.isNotBlank()) prefs.deviceLabel = serverLabel
                        refreshIdentityText(serverLabel)
                        updateFamilyButtons()
                    }
                }
                .onFailure { err ->
                    runOnUiThread {
                        refreshIdentityText()
                        if (err is CasaTrackApiException && err.statusCode == 404) {
                            toast("El backend de CasaTrack todavía necesita la actualización familiar")
                        }
                    }
                }
        }.start()
    }

    private fun updateFamilyButtons() {
        if (!prefs.configured()) return
        claimAdmin.visibility = if (!prefs.isAdmin && canClaimAdmin) View.VISIBLE else View.GONE
        inviteDevice.visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
        manageDevices.visibility = if (prefs.isAdmin) View.VISIBLE else View.GONE
    }

    private fun claimAdministration() {
        claimAdmin.isEnabled = false
        Thread {
            runCatching { FamilyClient.claimAdmin(prefs) }
                .onSuccess {
                    runOnUiThread {
                        prefs.isAdmin = true
                        canClaimAdmin = false
                        claimAdmin.isEnabled = true
                        refreshIdentityText()
                        updateFamilyButtons()
                        toast("Este teléfono ahora administra CasaTrack")
                    }
                }
                .onFailure { err -> runOnUiThread {
                    claimAdmin.isEnabled = true
                    toast(apiError(err))
                } }
        }.start()
    }

    private fun createInvitation() {
        inviteDevice.isEnabled = false
        Thread {
            runCatching { FamilyClient.createInvite(prefs) }
                .onSuccess { result ->
                    val token = result.getString("invite_token")
                    val expires = result.optLong("expires_at_ms", System.currentTimeMillis() + 600_000L)
                    val payload = Uri.Builder()
                        .scheme("casatrack")
                        .authority("enroll")
                        .appendQueryParameter("url", prefs.apiUrl)
                        .appendQueryParameter("invite", token)
                        .build()
                        .toString()
                    runOnUiThread {
                        inviteDevice.isEnabled = true
                        showInvitationQr(payload, expires)
                    }
                }
                .onFailure { err -> runOnUiThread {
                    inviteDevice.isEnabled = true
                    toast(apiError(err))
                } }
        }.start()
    }

    private fun showInvitationQr(payload: String, expiresAtMs: Long) {
        val density = resources.displayMetrics.density
        val size = (280 * density).toInt().coerceAtMost(1000)
        val bitmap = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, size, size)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (16 * density).toInt()
            setPadding(p, p, p, p)
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        })
        box.addView(TextView(this).apply {
            text = "En el teléfono nuevo: CasaTrack → Escanear invitación.\nLa invitación vence a las ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(expiresAtMs))}."
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle("Invitar otro teléfono")
            .setView(box)
            .setPositiveButton("Listo", null)
            .show()
    }

    private fun scanInvitation() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Escaneá el QR de CasaTrack")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        qrLauncher.launch(options)
    }

    private fun handleInvitationQr(content: String) {
        val uri = runCatching { Uri.parse(content) }.getOrNull()
        if (uri?.scheme != "casatrack" || uri.host != "enroll") {
            toast("Ese QR no es una invitación CasaTrack")
            return
        }
        val url = uri.getQueryParameter("url")?.trim().orEmpty()
        val invite = uri.getQueryParameter("invite")?.trim().orEmpty()
        if (!url.startsWith("https://") || invite.isBlank()) {
            toast("Invitación incompleta")
            return
        }
        api?.setText(url)
        prefs.apiUrl = url
        pendingInvite = invite
        invitationStatus?.text = "Invitación lista · completá el nombre y registrá este teléfono"
        toast("Invitación recibida")
    }

    private fun enrollThisPhone() {
        val invite = pendingInvite
        val url = api?.text?.toString()?.trim().orEmpty().trimEnd('/')
        val personName = person?.text?.toString()?.trim().orEmpty()
        val label = deviceLabel?.text?.toString()?.trim().orEmpty()

        if (invite.isNullOrBlank()) { toast("Primero escaneá una invitación"); return }
        if (!url.startsWith("https://")) { toast("La URL del Worker no es válida"); return }
        if (personName.isBlank()) { toast("Ingresá el nombre de la persona"); return }
        if (label.isBlank()) { toast("Ingresá un nombre para el teléfono"); return }

        invitationStatus?.text = "Registrando…"
        Thread {
            runCatching { FamilyClient.enroll(url, invite, personName, label) }
                .onSuccess { result ->
                    prefs.saveEnrollment(
                        apiUrl = url,
                        deviceId = result.getString("device_id"),
                        personName = result.getString("person_name"),
                        label = result.optString("label", label),
                        token = result.getString("device_token"),
                    )
                    runOnUiThread {
                        Toast.makeText(this, "Teléfono registrado", Toast.LENGTH_LONG).show()
                        recreate()
                    }
                }
                .onFailure { err -> runOnUiThread {
                    invitationStatus?.text = "No se pudo registrar"
                    toast(apiError(err))
                } }
        }.start()
    }

    private fun showFamilyDevices() {
        manageDevices.isEnabled = false
        Thread {
            runCatching { FamilyClient.listDevices(prefs) }
                .onSuccess { result ->
                    val array = result.optJSONArray("devices")
                    val devices = mutableListOf<JSONObject>()
                    if (array != null) {
                        for (i in 0 until array.length()) devices += array.getJSONObject(i)
                    }
                    runOnUiThread {
                        manageDevices.isEnabled = true
                        if (devices.isEmpty()) toast("No hay dispositivos") else showDeviceListDialog(devices)
                    }
                }
                .onFailure { err -> runOnUiThread {
                    manageDevices.isEnabled = true
                    toast(apiError(err))
                } }
        }.start()
    }

    private fun showDeviceListDialog(devices: List<JSONObject>) {
        val labels = devices.map { d ->
            val personName = d.optString("person_name", "Sin nombre")
            val label = d.optString("label", "Android")
            val admin = if (d.optInt("is_admin", 0) == 1) " · admin" else ""
            val last = d.optLong("server_time_ms", 0L)
            val seen = if (last > 0) "\nÚltima señal: ${relativeTime(last)}" else "\nSin ubicación todavía"
            "$personName — $label$admin$seen"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Dispositivos de la familia")
            .setItems(labels) { _, which -> showDeviceDetails(devices[which]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showDeviceDetails(device: JSONObject) {
        val id = device.optString("device_id")
        val personName = device.optString("person_name", "Sin nombre")
        val label = device.optString("label", "Android")
        val battery = device.optInt("battery_pct", -1)
        val activity = device.optString("activity", "—")
        val last = device.optLong("server_time_ms", 0L)
        val message = buildString {
            append("$personName\n$label\n")
            append("Última señal: ${if (last > 0) relativeTime(last) else "—"}\n")
            append("Actividad: $activity\n")
            append("Batería: ${if (battery >= 0) "$battery %" else "—"}")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Dispositivo")
            .setMessage(message)
            .setPositiveButton("Cerrar", null)

        if (id.isNotBlank() && id != prefs.deviceId) {
            builder.setNegativeButton("Eliminar") { _, _ -> confirmDeleteDevice(id, personName, label) }
        }
        builder.show()
    }

    private fun confirmDeleteDevice(id: String, personName: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar dispositivo")
            .setMessage("¿Eliminar $personName — $label de CasaTrack? Su token se revoca inmediatamente y también se elimina su historial de ubicación.")
            .setPositiveButton("Eliminar") { _, _ -> deleteDevice(id) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteDevice(id: String) {
        Thread {
            runCatching { FamilyClient.deleteDevice(prefs, id) }
                .onSuccess { runOnUiThread {
                    toast("Dispositivo eliminado")
                    showFamilyDevices()
                } }
                .onFailure { err -> runOnUiThread { toast(apiError(err)) } }
        }.start()
    }

    private fun toggleTracking() {
        if (!prefs.configured()) return
        if (prefs.trackingEnabled) {
            prefs.trackingEnabled = false
            TrackingService.stop(this)
        } else {
            if (!hasFine()) { requestCorePermissions(); return }
            prefs.trackingEnabled = true
            TrackingService.start(this)
        }
        refreshStatus()
    }

    private fun captureAnchor() {
        if (!hasFine()) { requestCorePermissions(); return }
        val w = WifiProbe.current(this)
        if (w.ssid.isNullOrBlank()) { toast("No pude leer el Wi‑Fi actual. Verificá Ubicación y permisos."); return }
        val req = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(15_000)
            .build()
        LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(req, null).addOnCompleteListener { task ->
            val loc: Location? = task.result
            if (loc == null) { toast("No conseguí una ubicación fresca"); return@addOnCompleteListener }
            prefs.anchorSsid = w.ssid
            prefs.anchorBssids = w.bssid ?: ""
            prefs.anchorLat = loc.latitude
            prefs.anchorLon = loc.longitude
            refreshAnchorText()
            toast("Ubicación de casa guardada")
            if (prefs.trackingEnabled) {
                GeofenceSupport.unregister(this)
                GeofenceSupport.registerHome(this, prefs)
            }
        }
    }

    private fun requestCorePermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 29) wanted += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 90)
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Después elegí Ubicación → Permitir todo el tiempo.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else if (Build.VERSION.SDK_INT == 29 && hasFine()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 91)
        }
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshAnchorText() {
        if (!::anchor.isInitialized) return
        anchor.text = if (prefs.hasAnchor()) {
            "Casa: ${prefs.anchorSsid} · ${"%.5f".format(prefs.anchorLat)}, ${"%.5f".format(prefs.anchorLon)}"
        } else {
            "Ubicación de casa sin configurar"
        }
    }

    private fun refreshStatus() {
        refreshIdentityText()
        if (!prefs.configured() || !::status.isInitialized || !::startStop.isInitialized) return
        status.text = "Seguimiento: ${if (prefs.trackingEnabled) "ACTIVO" else "detenido"}\n" +
            "Actividad: ${prefs.activity}\n" +
            "Último fix: ${if (prefs.lastFixAt > 0) relativeTime(prefs.lastFixAt) else "—"}"
        startStop.text = if (prefs.trackingEnabled) "Detener seguimiento" else "Iniciar seguimiento"
    }

    private fun relativeTime(timeMs: Long): String {
        val delta = (System.currentTimeMillis() - timeMs).coerceAtLeast(0L)
        return when {
            delta < 60_000L -> "ahora"
            delta < 3_600_000L -> "hace ${delta / 60_000L} min"
            delta < 86_400_000L -> "hace ${delta / 3_600_000L} h"
            else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(Date(timeMs))
        }
    }

    private fun apiError(err: Throwable): String {
        if (err is CasaTrackApiException) {
            return when (err.apiError) {
                "invite_invalid_or_expired" -> "La invitación venció o ya fue usada"
                "admin_required" -> "Este teléfono no es administrador"
                "admin_already_exists" -> "Ya existe otro administrador"
                "cannot_delete_current_admin" -> "No podés eliminar el administrador desde sí mismo"
                else -> "CasaTrack: ${err.apiError}"
            }
        }
        return err.message?.takeIf { it.isNotBlank() } ?: "No se pudo conectar con CasaTrack"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
