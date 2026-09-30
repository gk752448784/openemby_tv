package com.xxxx.emby_tv

import com.xxxx.emby_tv.util.PlaybackRequest
import com.xxxx.emby_tv.util.NetworkRetryPolicy
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

// Can also run with the standalone Kotlin compiler, without an Android device.
fun main() {
    var checks = 0
    fun expect(expected: Any?, actual: Any?) {
        check(expected == actual) { "Expected $expected, got $actual" }
        checks++
    }
    expect("https://cdn.test/movie.mkv?sig=abc", PlaybackRequest.resolveUrl("https://emby.test", "https://cdn.test/movie.mkv?sig=abc"))
    expect("https://emby.test/emby/Videos/1/stream", PlaybackRequest.resolveUrl("https://emby.test/", "/Videos/1/stream"))
    expect("https://emby.test/emby/Videos/1/stream", PlaybackRequest.resolveUrl("https://emby.test", "/emby/Videos/1/stream"))
    expect("https://emby.test/emby/Videos/1/stream", PlaybackRequest.resolveUrl("https://emby.test/emby", "Videos/1/stream"))
    expect("https://emby.test/proxy/emby/Videos/1/stream", PlaybackRequest.resolveUrl("https://emby.test/proxy", "/Videos/1/stream"))
    expect("https://emby.test/proxy/emby/Videos/1/stream", PlaybackRequest.resolveUrl("https://emby.test/proxy", "/proxy/emby/Videos/1/stream"))
    expect("https://cdn.test/movie.mkv", PlaybackRequest.resolveUrl("https://emby.test", "//cdn.test/movie.mkv"))
    expect("transcode", PlaybackRequest.selectPath("direct", "transcode", true))
    expect("direct", PlaybackRequest.selectPath("direct", "transcode", false))
    expect("transcode", PlaybackRequest.selectPath("", "transcode", false))
    expect(null, PlaybackRequest.selectPath(null, null, false))
    expect(true, PlaybackRequest.shouldFallback(404, false))
    expect(true, PlaybackRequest.shouldFallback(410, false))
    expect(false, PlaybackRequest.shouldFallback(404, true))
    expect(false, PlaybackRequest.shouldFallback(401, false))
    expect(false, PlaybackRequest.shouldFallback(503, false))
    expect("https://cdn.test/video?x=1&api_key=a%2Bb", PlaybackRequest.withApiKey("https://cdn.test/video?x=1", "a+b", true))
    expect("https://cdn.test/video?api_key=existing", PlaybackRequest.withApiKey("https://cdn.test/video?api_key=existing", "secret", true))
    expect("https://cdn.test/video", PlaybackRequest.withApiKey("https://cdn.test/video", "secret", false))
    expect(1_000L, NetworkRetryPolicy.delayMs(0, SocketTimeoutException()))
    expect(3_000L, NetworkRetryPolicy.delayMs(1, SocketTimeoutException()))
    expect(8_000L, NetworkRetryPolicy.delayMs(2, SocketTimeoutException()))
    expect(null, NetworkRetryPolicy.delayMs(3, SocketTimeoutException()))
    expect(null, NetworkRetryPolicy.delayMs(0, SSLHandshakeException("bad certificate")))
    expect(null, NetworkRetryPolicy.delayMs(0, IllegalArgumentException()))
    expect(null, NetworkRetryPolicy.delayMs(0, SocketTimeoutException(), 404))
    expect(1_000L, NetworkRetryPolicy.delayMs(0, RuntimeException(), 503))
    println("PASS: $checks playback/network regression checks")
}
