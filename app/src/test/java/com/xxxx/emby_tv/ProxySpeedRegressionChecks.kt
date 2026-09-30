package com.xxxx.emby_tv

import com.xxxx.emby_tv.data.remote.ProxySpeedTest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

// Application interceptor returns synthetic Emby responses; no socket or real server is used.
fun proxySpeedRegressionChecks() = runBlocking {
    var status = 200
    var emptyLibrary = false
    val requests = mutableListOf<String>()
    val client = OkHttpClient.Builder()
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1080)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
        })
        .addInterceptor { chain ->
            val request = chain.request()
            check(request.header("X-Emby-Token") == "test-token")
            check(request.header("Cache-Control") == "no-cache, no-store")
            requests.add(request.url.encodedPath)
            val body = when {
                request.url.encodedPath.endsWith("/System/Info/Public") -> "{\"ServerName\":\"test\"}".toResponseBody()
                request.url.encodedPath.endsWith("/Items") ->
                    (if (emptyLibrary) "{\"Items\":[]}" else "{\"Items\":[{\"Id\":\"1\"}]}").toResponseBody()
                else -> ByteArray(3 * 1024 * 1024).toResponseBody()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body).build()
        }.build()
    try {
        val full = ProxySpeedTest.run(client, "https://emby.test", "user", "test-token")
        check(full.usesProxy)
        check(full.bytes == 2 * 1024 * 1024L) { "Sample must stop at 2 MiB" }
        check(full.mbps != null && full.mbps > 0)
        check(requests.size == 3)
        emptyLibrary = true
        val empty = ProxySpeedTest.run(client, "https://emby.test", "user", "test-token")
        check(empty.mbps == null && empty.bytes == 0L)
        status = 407
        try {
            ProxySpeedTest.run(client, "https://emby.test", "user", "test-token")
            error("HTTP 407 must not count as success")
        } catch (e: ProxySpeedTest.HttpError) {
            check(e.status == 407)
        }
        println("PASS: proxy speed sample cap, token/cache headers, route, empty library and HTTP 407 checks")
    } finally {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

fun main() = proxySpeedRegressionChecks()
