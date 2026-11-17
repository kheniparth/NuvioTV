package com.nuvio.tv.livetv.data

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class LiveTvRawResponse(val code: Int, val body: String)

/**
 * The app OkHttpClient replaces User-Agent and may cache GETs. Portal calls need their own
 * agent (Stalker rejects anything but a MAG box) and a fresh token, so both are undone here.
 */
@Singleton
class LiveTvHttp @Inject constructor(client: OkHttpClient) {
    private val http = client.newBuilder()
        .cache(null)
        .addInterceptor { chain ->
            val override = chain.request().header(USER_AGENT_OVERRIDE) ?: return@addInterceptor chain.proceed(chain.request())
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", override)
                    .removeHeader(USER_AGENT_OVERRIDE)
                    .build()
            )
        }
        .build()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val response = requestRaw("GET", url, headers)
        if (response.code !in 200..299) error("HTTP ${response.code}")
        return response.body
    }

    /** Raw bytes so a gzip XMLTV file is not decoded as text. */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            http.newCall(buildRequest("GET", url, headers, "")).execute().use { response ->
                if (response.code !in 200..299) error("HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        }

    suspend fun requestRaw(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
    ): LiveTvRawResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(buildRequest(method, url, headers, body))
        // cancel() interrupts a hanging read, so a caller's withTimeout ends at that timeout.
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { LiveTvRawResponse(it.code, it.body?.string().orEmpty()) }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun buildRequest(method: String, url: String, headers: Map<String, String>, body: String): Request {
        val noBody = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        return Request.Builder()
            .url(url)
            .method(method, if (noBody) null else body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .apply {
                headers.forEach { (key, value) ->
                    header(key, value)
                    if (key.equals("User-Agent", ignoreCase = true)) header(USER_AGENT_OVERRIDE, value)
                }
            }
            .build()
    }

    private companion object {
        const val USER_AGENT_OVERRIDE = "X-LiveTv-User-Agent"
    }
}

internal fun liveTvEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")
