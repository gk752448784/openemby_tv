package com.xxxx.emby_tv.util

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

object NetworkRetryPolicy {
    fun delayMs(attempt: Int, error: Throwable, status: Int? = null): Long? {
        val delay = when (attempt) { 0 -> 1_000L; 1 -> 3_000L; 2 -> 8_000L; else -> return null }
        if (status != null) return delay.takeIf { status == 408 || status == 429 || status in 500..599 }
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.any { it is SSLHandshakeException || it is SSLPeerUnverifiedException }) return null
        return delay.takeIf { causes.any { it is IOException } }
    }
}
