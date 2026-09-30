package com.xxxx.emby_tv.util

import java.net.URI
import java.net.URLEncoder

object PlaybackRequest {
    fun shouldFallback(status: Int?, alreadyTried: Boolean): Boolean =
        !alreadyTried && (status == 404 || status == 410)

    fun selectPath(direct: String?, transcode: String?, forceTranscode: Boolean): String? {
        val directPath = direct?.takeIf { it.isNotBlank() }
        val transcodePath = transcode?.takeIf { it.isNotBlank() }
        return if (forceTranscode) transcodePath ?: directPath else directPath ?: transcodePath
    }

    fun resolveUrl(serverUrl: String, path: String): String {
        val relative = URI(path)
        if (relative.isAbsolute) {
            require(relative.scheme.equals("http", true) || relative.scheme.equals("https", true))
            return path
        }
        val base = URI(serverUrl.trimEnd('/'))
        if (path.startsWith("//")) return "${base.scheme}:$path"
        val serverPath = base.rawPath.orEmpty().trimEnd('/')
        val apiPath = if (serverPath.endsWith("/emby", true)) serverPath else "$serverPath/emby"
        val cleanPath = path.trimStart('/')
        val resolvedPath = when {
            cleanPath.startsWith(apiPath.trimStart('/') + "/", true) -> "/$cleanPath"
            cleanPath.startsWith("emby/", true) -> "${apiPath.removeSuffix("/emby")}/$cleanPath"
            else -> "$apiPath/$cleanPath"
        }
        return "${base.scheme}://${base.rawAuthority}$resolvedPath"
    }

    fun withApiKey(url: String, apiKey: String, addApiKey: Boolean): String {
        if (!addApiKey || apiKey.isEmpty()) return url
        val uri = URI(url)
        val hasToken = uri.rawQuery.orEmpty().split('&').any {
            val name = it.substringBefore('=')
            name.equals("api_key", true) || name.equals("X-Emby-Token", true)
        }
        if (hasToken) return url
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val withoutFragment = url.substringBefore('#')
        val separator = if (uri.rawQuery == null) "?" else "&"
        return "$withoutFragment${separator}api_key=${URLEncoder.encode(apiKey, "UTF-8")}$fragment"
    }
}
