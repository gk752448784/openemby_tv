package com.xxxx.emby_tv.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.URI
import kotlin.coroutines.resumeWithException

object ProxySpeedTest {
    enum class Target(val url: String) {
        DOMESTIC("https://www.baidu.com/"),
        OVERSEAS("https://www.gstatic.com/generate_204")
    }

    enum class Failure { HTTP, CONNECTION }

    data class Result(val target: Target, val latencyMs: Long?, val usesProxy: Boolean,
                      val httpStatus: Int? = null, val failure: Failure? = null)

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

    /** HTTP response latency through the draft proxy; no Emby credentials or body download. */
    suspend fun run(client: OkHttpClient): List<Result> = withContext(Dispatchers.IO) {
        val probeClient = client.newBuilder()
            .cache(null)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        coroutineScope {
            Target.entries.map { target -> async { probe(probeClient, target) } }.awaitAll()
        }
    }

    private suspend fun probe(client: OkHttpClient, target: Target): Result {
        val request = Request.Builder().url(target.url).head()
            .header("Cache-Control", "no-cache, no-store").build()
        val usesProxy = client.proxy?.let { it.type() != Proxy.Type.DIRECT }
            ?: client.proxySelector.select(URI(target.url)).any { it.type() != Proxy.Type.DIRECT }
        val started = System.nanoTime()
        return try {
            execute(client, request).use { response ->
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (response.isSuccessful) Result(target, elapsedMs, usesProxy, response.code)
                else Result(target, null, usesProxy, response.code, Failure.HTTP)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Result(target, null, usesProxy, failure = Failure.CONNECTION)
        }
    }
}
