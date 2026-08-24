package com.casatrack.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CasaTrackApiException(val statusCode: Int, val apiError: String) : IOException(apiError)

object FamilyClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun authenticatedBuilder(prefs: AppPrefs, path: String): Request.Builder {
        if (!prefs.configured()) throw IllegalStateException("CasaTrack no está registrado")
        return Request.Builder()
            .url("${prefs.apiUrl}$path")
            .header("Authorization", "Bearer ${prefs.deviceToken}")
            .header("X-CasaTrack-Device-ID", prefs.deviceId)
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                val error = parsed.optString("error").ifBlank { "HTTP ${response.code}" }
                throw CasaTrackApiException(response.code, error)
            }
            return parsed
        }
    }

    fun me(prefs: AppPrefs): JSONObject = execute(
        authenticatedBuilder(prefs, "/v1/me").get().build()
    )

    fun claimAdmin(prefs: AppPrefs): JSONObject = execute(
        authenticatedBuilder(prefs, "/v1/admin/claim")
            .post("{}".toRequestBody(jsonType))
            .build()
    )

    fun createInvite(prefs: AppPrefs): JSONObject = execute(
        authenticatedBuilder(prefs, "/v1/invites")
            .post("{}".toRequestBody(jsonType))
            .build()
    )

    fun listDevices(prefs: AppPrefs): JSONObject = execute(
        authenticatedBuilder(prefs, "/v1/devices").get().build()
    )

    fun deleteDevice(prefs: AppPrefs, deviceId: String): JSONObject = execute(
        authenticatedBuilder(prefs, "/v1/devices/$deviceId").delete().build()
    )

    fun enroll(apiUrl: String, inviteToken: String, personName: String, label: String): JSONObject {
        val body = JSONObject()
            .put("invite_token", inviteToken)
            .put("person_name", personName)
            .put("label", label)
            .toString()
        val request = Request.Builder()
            .url("${apiUrl.trim().trimEnd('/')}/v1/enroll")
            .post(body.toRequestBody(jsonType))
            .build()
        return execute(request)
    }
}
