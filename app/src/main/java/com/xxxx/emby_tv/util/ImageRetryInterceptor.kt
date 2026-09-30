package com.xxxx.emby_tv.util

import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.delay

/** Retry transient image failures, retaining successful image caches and cancellation. */
class ImageRetryInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        var attempt = 0
        while (true) {
            val result = chain.proceed()
            if (result !is ErrorResult) return result
            val status = (result.throwable as? HttpException)?.response?.code
            val waitMs = NetworkRetryPolicy.delayMs(attempt++, result.throwable, status) ?: return result
            delay(waitMs)
        }
    }
}
