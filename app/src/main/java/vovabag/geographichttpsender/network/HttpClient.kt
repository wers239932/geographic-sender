package vovabag.geographichttpsender.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import vovabag.geographichttpsender.model.HttpConfig
import java.util.concurrent.TimeUnit

private const val TAG = "HttpClient"

class HttpClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun sendRequest(config: HttpConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "📤 Отправка запроса: ${config.method} ${config.url}")
            
            val requestBuilder = Request.Builder().url(config.url)

            // Добавляем заголовки
            config.headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
                Log.d(TAG, "  Header: $key: $value")
            }

            // Добавляем тело запроса для методов кроме GET
            if (config.method != vovabag.geographichttpsender.model.HttpMethod.GET) {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = config.body?.toRequestBody(mediaType) ?: "".toRequestBody(mediaType)
                when (config.method) {
                    vovabag.geographichttpsender.model.HttpMethod.POST -> requestBuilder.post(body)
                    vovabag.geographichttpsender.model.HttpMethod.PUT -> requestBuilder.put(body)
                    vovabag.geographichttpsender.model.HttpMethod.DELETE -> requestBuilder.delete(body)
                    vovabag.geographichttpsender.model.HttpMethod.PATCH -> requestBuilder.patch(body)
                    else -> requestBuilder.get()
                }
            } else {
                requestBuilder.get()
            }

            val request = requestBuilder.build()
            Log.d(TAG, "  Полный URL: ${request.url}")
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "  ✅ Ответ: ${response.code} ${response.message}")
                Log.d(TAG, "  Body: ${responseBody.take(200)}")
                
                if (response.isSuccessful) {
                    Result.success(responseBody)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Ошибка запроса: ${e.message}", e)
            Result.failure(e)
        }
    }
}
