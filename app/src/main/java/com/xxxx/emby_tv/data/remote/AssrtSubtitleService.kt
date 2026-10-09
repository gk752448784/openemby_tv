package com.xxxx.emby_tv.data.remote

import android.content.Context
import com.xxxx.emby_tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AssrtSubtitleService {
    private const val API = "https://api.assrt.net/v1/sub/"

    private suspend fun fetch(context: Context, request: Request, limit: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = HttpClient.getClient(context).newCall(request)
            call.timeout().timeout(45, java.util.concurrent.TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("ASSRT: connection failed"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw IOException("ASSRT: HTTP ${it.code}")
                            val body = it.body ?: throw IOException("ASSRT: empty response")
                            if (body.contentLength() > limit) throw IOException("ASSRT: response too large")
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (continuation.isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > limit) throw IOException("ASSRT: response too large")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    private suspend fun api(context: Context, endpoint: String, key: String, value: String): String {
        check(BuildConfig.ASSRT_TOKEN.isNotBlank()) { "ASSRT: token missing from this build" }
        val url = (API + endpoint).toHttpUrl().newBuilder().addQueryParameter(key, value)
            .addQueryParameter("cnt", "15").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${BuildConfig.ASSRT_TOKEN}")
            .header("Cache-Control", "no-store").build()
        return fetch(context, request, 1024 * 1024).toString(Charsets.UTF_8)
    }

    suspend fun search(context: Context, query: String): List<AssrtSearchResult> {
        require(query.trim().length >= 3) { "ASSRT: enter at least 3 characters" }
        return AssrtSubtitles.parseSearch(api(context, "search", "q", query.trim()))
    }

    suspend fun files(context: Context, id: String): List<AssrtFile> =
        AssrtSubtitles.parseFiles(api(context, "detail", "id", id))

    suspend fun download(context: Context, file: AssrtFile): LocalSubtitle {
        val codec = AssrtSubtitles.codec(file.name) ?: error("ASSRT: unsupported subtitle format")
        // Download requests never contain ASSRT or Emby credentials.
        val bytes = fetch(context, Request.Builder().url(file.url).build(), AssrtSubtitles.MAX_BYTES)
        return withContext(Dispatchers.IO) {
            val normalized = AssrtSubtitles.normalize(bytes, codec)
            val directory = File(context.cacheDir, "assrt_subtitles").apply { mkdirs() }
            // Keep the bounded local cache; download URLs are deliberately not persisted.
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(19)?.forEach { it.delete() }
            val local = File.createTempFile("subtitle-", ".$codec", directory)
            local.writeBytes(normalized)
            LocalSubtitle(local.absolutePath, file.name, codec)
        }
    }
}
