package com.xxxx.emby_tv.data.remote

import android.content.Context
import android.util.Log
import com.xxxx.emby_tv.data.local.PreferencesManager
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Route
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

object HttpClient {
    private const val TAG = "HttpClient"

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var proxyConfig: ProxyConfig? = null

    @Volatile
    private var cache: Cache? = null

    private fun getCache(context: Context): Cache {
        return cache ?: synchronized(this) {
            cache ?: Cache(File(context.cacheDir, "http_cache"), 250L * 1024 * 1024).also { cache = it }
        }
    }

    fun getClient(context: Context): OkHttpClient {
        val currentConfig = loadProxyConfig(context)
        if (client != null && proxyConfig == currentConfig) {
            return client!!
        }
        synchronized(this) {
            if (client != null && proxyConfig == currentConfig) {
                return client!!
            }
            proxyConfig = currentConfig
            client = createClient(context, currentConfig)
            return client!!
        }
    }

    private fun loadProxyConfig(context: Context): ProxyConfig {
        val prefs = PreferencesManager(context)
        return if (prefs.proxyEnabled && prefs.proxyHost.isNotEmpty()) {
            ProxyConfig(
                enabled = true,
                type = prefs.proxyType,
                host = prefs.proxyHost,
                port = prefs.proxyPort,
                username = prefs.proxyUsername,
                password = prefs.proxyPassword
            )
        } else {
            ProxyConfig()
        }
    }

    private fun isLocalAddress(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1") return true
        if (host.endsWith(".local", ignoreCase = true)) return true
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (host.startsWith("172.")) {
            val parts = host.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) return true
            }
        }
        return false
    }

    /** Isolated client for draft settings; does not save settings or cancel active playback. */
    fun createTestClient(context: Context, type: String, host: String, port: Int,
                         username: String, password: String): OkHttpClient {
        require(host.isNotBlank() && port in 1..65535)
        return createClient(context, ProxyConfig(true, type, host.trim(), port, username, password), false)
            .newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun createClient(context: Context, config: ProxyConfig, useCache: Boolean = true): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cache(if (useCache) getCache(context) else null)
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 20
            })
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES)) // 远程代理场景延长保活，减少重建握手
            .connectTimeout(20, TimeUnit.SECONDS) // 代理握手 + 远程连接两段延迟，6s 不够
            .readTimeout(40, TimeUnit.SECONDS)   // 大响应体（PlaybackInfo）在慢速代理下需要更长时间
            .retryOnConnectionFailure(true)

        if (config.enabled && config.host.isNotEmpty()) {
            val proxy = if (config.type == "socks5") {
                Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(config.host, config.port))
            } else {
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(config.host, config.port))
            }

            builder.proxySelector(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    return try {
                        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
                        // 本地及局域网请求（如 LocalServer 扫码登录、局域网设备）走直连
                        if (isLocalAddress(host)) {
                            return listOf(Proxy.NO_PROXY)
                        }
                        // 其余所有远程请求（Emby API、302 跳转的媒体流、海报等）统一走代理
                        listOf(proxy)
                    } catch (e: Exception) {
                        Log.e(TAG, "ProxySelector.select error", e)
                        listOf(Proxy.NO_PROXY)
                    }
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                    Log.e(TAG, "Proxy connectFailed: $uri", ioe)
                }
            })

            if (config.username.isNotEmpty()) {
                val credential = Credentials.basic(config.username, config.password)
                builder.proxyAuthenticator(object : Authenticator {
                    override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
                        if (response.request.header("Proxy-Authorization") != null) {
                            return null
                        }
                        return response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                })
            }

            Log.i(TAG, "代理已启用: ${config.type}://${config.host}:${config.port}")
        }

        return builder.build()
    }

    fun rebuildClient(context: Context) {
        try {
            synchronized(this) {
                val oldClient = client
                client = null
                proxyConfig = null
                oldClient?.dispatcher?.cancelAll()
            }
            getClient(context)
            Log.i(TAG, "HttpClient 已重建")
        } catch (e: Exception) {
            Log.e(TAG, "重建 HttpClient 失败", e)
            synchronized(this) {
                client = null
                proxyConfig = null
            }
        }
    }

    private data class ProxyConfig(
        val enabled: Boolean = false,
        val type: String = "http",
        val host: String = "",
        val port: Int = 1080,
        val username: String = "",
        val password: String = ""
    )
}
