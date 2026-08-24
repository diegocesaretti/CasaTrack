package com.casatrack.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * One-time bridge for prototype builds that were signed with ephemeral debug keys.
 * Once a stable signing path is in place this screen remains harmless: configured
 * installs skip it immediately, while brand-new phones can continue to QR enrollment.
 */
class MigrationActivity : AppCompatActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var recovery: LinearLayout
    private lateinit var api: EditText
    private lateinit var deviceId: EditText
    private lateinit var token: EditText
    private lateinit var person: EditText
    private lateinit var label: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        if (prefs.configured()) {
            openMain()
            return
        }
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun field(hint: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun button(text: String, click: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { click() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(28))
        }
        root.addView(TextView(this).apply { text = "CasaTrack"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Configuración del teléfono"
            textSize = 16f
            setPadding(0, dp(4), 0, dp(18))
        })

        root.addView(button("Es un teléfono nuevo") { openMain() })
        root.addView(TextView(this).apply {
            text = "Usá esta opción para agregarlo escaneando la invitación QR desde otro CasaTrack."
            textSize = 12f
            setPadding(0, 0, 0, dp(14))
        })

        root.addView(button("Ya usaba CasaTrack · recuperar configuración") {
            recovery.visibility = if (recovery.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })

        recovery = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        recovery.addView(TextView(this).apply {
            text = "Antes de desinstalar la versión anterior, copiá estos tres datos de su pantalla: Worker URL, ID del dispositivo y Token del dispositivo. Esto se hace una sola vez por el cambio de firma del prototipo."
            textSize = 13f
        })
        api = field("Worker URL · https://...workers.dev")
        deviceId = field("ID del dispositivo anterior")
        token = field("Token del dispositivo anterior", true)
        person = field("Nombre de la persona")
        label = field("Nombre del teléfono")
        recovery.addView(api)
        recovery.addView(deviceId)
        recovery.addView(token)
        recovery.addView(person)
        recovery.addView(label)
        recovery.addView(button("Recuperar este dispositivo") { recover() })
        root.addView(recovery)

        return ScrollView(this).apply { addView(root) }
    }

    private fun recover() {
        val url = api.text.toString().trim().trimEnd('/')
        val id = deviceId.text.toString().trim()
        val rawToken = token.text.toString().trim()
        val personName = person.text.toString().trim()
        val phoneLabel = label.text.toString().trim().ifBlank { "Android" }

        if (!url.startsWith("https://")) { toast("Worker URL inválida"); return }
        if (!Regex("^[A-Za-z0-9._-]{2,80}$").matches(id)) { toast("ID del dispositivo inválido"); return }
        if (rawToken.isBlank()) { toast("Falta el token del dispositivo"); return }
        if (personName.isBlank()) { toast("Falta el nombre de la persona"); return }

        prefs.saveEnrollment(url, id, personName, phoneLabel, rawToken)
        toast("Configuración recuperada")
        openMain()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
