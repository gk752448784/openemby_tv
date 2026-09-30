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
import java.io.IOException

// Synthetic responses: no socket, Emby server, account or credentials are needed.
fun proxySpeedRegressionChecks() = runBlocking {
    var domesticStatus = 200
    var failOverseas = false
    val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    val client = OkHttpClient.Builder()
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 1080)))
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        .addInterceptor { chain ->
            val request = chain.request()
            check(request.header("X-Emby-Token") == null)
            check(request.header("Authorization") == null)
            check(request.header("Cache-Control") == "no-cache, no-store")
            check(request.method == "HEAD")
            requests.add(request.url.host)
            val domestic = request.url.host == "www.baidu.com"
            if (!domestic && failOverseas) throw IOException("synthetic timeout")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (domestic) domesticStatus else 204).message("test")
                .body("".toResponseBody()).build()
        }.build()
    try {
        val results = ProxySpeedTest.run(client)
        check(results.map { it.target } == ProxySpeedTest.Target.entries.toList())
        check(results.all { it.usesProxy && it.failure == null && it.latencyMs != null && it.latencyMs >= 0 })
        check(requests.size == 2 && requests.toSet() == setOf("www.baidu.com", "www.gstatic.com"))
        domesticStatus = 407
        val partial = ProxySpeedTest.run(client)
        check(partial[0].failure == ProxySpeedTest.Failure.HTTP && partial[0].httpStatus == 407 && partial[0].latencyMs == null)
        check(partial[1].failure == null && partial[1].httpStatus == 204)
        domesticStatus = 200
        failOverseas = true
        val disconnected = ProxySpeedTest.run(client)
        check(disconnected[0].failure == null)
        check(disconnected[1].failure == ProxySpeedTest.Failure.CONNECTION && disconnected[1].latencyMs == null)
        val direct = ProxySpeedTest.run(client.newBuilder().proxy(Proxy.NO_PROXY).build())
        check(direct.none { it.usesProxy })
        println("PASS: no-login domestic/overseas latency, HEAD/no-token/no-cache, independent HTTP/connection errors and direct route checks")
    } finally {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

fun main() = proxySpeedRegressionChecks()
