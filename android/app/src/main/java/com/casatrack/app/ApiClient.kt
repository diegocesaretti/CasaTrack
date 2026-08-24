package com.casatrack.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun send(prefs: AppPrefs, payload: String): Boolean {
        if (!prefs.configured()) return false
        val req = Request.Builder()
            .url("${prefs.apiUrl}/v1/update")
            .header("Authorization", "Bearer ${prefs.deviceToken}")
            .post(payload.toRequestBody(jsonType))
            .build()
        return runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    fun sendOrQueue(context: Context, telemetry: Telemetry) {
        val payload = telemetry.toJson().toString()
        Thread {
            val prefs = AppPrefs(context)
            if (!send(prefs, payload)) queue(context, payload)
        }.start()
    }

    private fun queue(context: Context, payload: String) {
        val input = Data.Builder().putString("payload", payload).build()
        val work = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val payload = inputData.getString("payload") ?: return Result.failure()
        val ok = ApiClient.send(AppPrefs(applicationContext), payload)
        return if (ok) Result.success() else Result.retry()
    }
}
