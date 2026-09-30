package com.xxxx.emby_tv.data.remote

import com.google.gson.JsonParser
import com.xxxx.emby_tv.util.PlaybackRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.URI
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ProxySpeedTest {
    data class Result(val latencyMs: Long, val usesProxy: Boolean, val bytes: Long = 0,
                      val mbps: Double? = null)
    class HttpError(val status: Int) : IOException("HTTP $status")

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    continuation.resume(response, onCancellation = { response.close() })
                }
            })
        }

    suspend fun run(client: OkHttpClient, serverUrl: String, userId: String, apiKey: String): Result =
        withContext(Dispatchers.IO) {
            fun request(path: String) = Request.Builder()
                .url(PlaybackRequest.resolveUrl(serverUrl, path))
                .header("X-Emby-Token", apiKey)
                .header("Cache-Control", "no-cache, no-store")
                .build()
            val ping = request("/System/Info/Public")
            val usesProxy = client.proxySelector.select(URI(ping.url.toString())).any { it.type() != Proxy.Type.DIRECT }
            val started = System.nanoTime()
            execute(client, ping).use {
                if (!it.isSuccessful) throw HttpError(it.code)
                // Bound the response read; a response header alone is not a successful Emby request.
                val body = it.body ?: throw IOException("Empty response")
                val input = body.byteStream()
                val sample = readBounded(input, 64 * 1024)
                JsonParser.parseString(sample.toString(Charsets.UTF_8)).asJsonObject
            }
            val latencyMs = (System.nanoTime() - started) / 1_000_000
            val itemsPath = "/Users/${java.net.URLEncoder.encode(userId, "UTF-8")}/Items" +
                "?Recursive=true&IncludeItemTypes=Movie,Series&ImageTypes=Primary&Limit=1"
            val item = execute(client, request(itemsPath)).use {
                if (!it.isSuccessful) throw HttpError(it.code)
                val bytes = it.body?.byteStream()?.let { stream -> readBounded(stream, 256 * 1024) }
                    ?: throw IOException("Empty response")
                JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                    .getAsJsonArray("Items")?.firstOrNull()?.asJsonObject
            } ?: return@withContext Result(latencyMs, usesProxy)
            val id = item.get("Id")?.asString ?: return@withContext Result(latencyMs, usesProxy)
            val downloadStarted = System.nanoTime()
            var downloaded = 0L
            execute(client, request("/Items/$id/Images/Primary?maxWidth=1920&quality=90")).use {
                if (!it.isSuccessful) throw HttpError(it.code)
                val body = it.body ?: throw IOException("Empty response")
                val buffer = ByteArray(16 * 1024)
                val input = body.byteStream()
                while (downloaded < 2 * 1024 * 1024 && System.nanoTime() - downloadStarted < 8_000_000_000L) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), 2 * 1024 * 1024 - downloaded).toInt())
                    if (count < 0) break
                    downloaded += count
                }
            }
            val seconds = (System.nanoTime() - downloadStarted) / 1_000_000_000.0
            Result(latencyMs, usesProxy, downloaded,
                if (downloaded > 0 && seconds > 0) downloaded * 8 / seconds / 1_000_000 else null)
        }
}
