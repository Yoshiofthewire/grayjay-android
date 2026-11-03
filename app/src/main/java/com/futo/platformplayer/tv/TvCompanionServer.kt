package com.futo.platformplayer.tv

import android.content.Context
import com.futo.platformplayer.api.http.server.HttpContext
import com.futo.platformplayer.api.http.server.ManagedHttpServer
import com.futo.platformplayer.api.http.server.handlers.HttpFunctionHandler
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateAssets
import com.futo.platformplayer.states.StatePlatform
import com.futo.platformplayer.states.StateSubscriptions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LAN server behind the TV's "Use your phone" QR code. Runs only while the companion
 * screen is open. Every /api call must carry the per-session secret from the QR code;
 * the secret travels in the URL fragment, so it never appears in a request line.
 * Requests run [onRequest] on a server thread, which returns the message shown on the phone
 * or throws [IllegalArgumentException] with a user-facing reason. Nothing that runs code
 * (plugin installs) happens without a confirmation on the TV.
 */
class TvCompanionServer(private val context: Context, private val onRequest: (Request) -> String) {
    sealed class Request {
        data class InstallPlugin(val url: String) : Request()
        data class Follow(val url: String) : Request()
        data class Search(val query: String) : Request()
        data class Play(val url: String) : Request()
    }

    @Serializable private data class UrlBody(val url: String)
    @Serializable private data class QueryBody(val query: String)
    @Serializable data class Creator(val name: String, val url: String, val thumbnail: String?, val following: Boolean)
    @Serializable private data class Source(val name: String)
    @Serializable private data class Info(val sources: List<Source>)

    private val json = Json { ignoreUnknownKeys = true }
    private var server: ManagedHttpServer? = null

    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val tokenBytes = token.toByteArray()

    /** Time of the last authorized request, for the idle timeout. */
    @Volatile var lastRequestAt = System.currentTimeMillis()
        private set

    /** The QR code target, or null when the TV has no LAN address. */
    var url: String? = null
        private set

    val port: Int get() = server?.port ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ManagedHttpServer(0)
        s.addHandler(HttpFunctionHandler("GET", "/") { it.respondCode(200, page(), "text/html; charset=utf-8") }.withHeader("Cache-Control", "no-store"))
        api(s, "GET", "/api/info") {
            it.respondJson(200, Info(StatePlatform.instance.getEnabledClients().map { c -> Source(c.name) }))
        }
        api(s, "GET", "/api/creators") {
            val q = decode(it.query["q"]).trim()
            if (q.isEmpty() || q.length > MAX_TEXT) return@api it.respondCode(400, "Bad query")
            val results = StatePlatform.instance.searchChannels(q).getResults().take(20).map { a ->
                Creator(a.name, a.url, a.thumbnail, StateSubscriptions.instance.isSubscribed(a.url))
            }
            it.respondJson(200, results)
        }
        api(s, "POST", "/api/plugin") { it.respondCode(200, onRequest(Request.InstallPlugin(httpUrl(body<UrlBody>(it).url)))) }
        api(s, "POST", "/api/follow") { it.respondCode(200, onRequest(Request.Follow(httpUrl(body<UrlBody>(it).url)))) }
        api(s, "POST", "/api/play") { it.respondCode(200, onRequest(Request.Play(httpUrl(body<UrlBody>(it).url)))) }
        api(s, "POST", "/api/search") {
            val q = body<QueryBody>(it).query.trim()
            require(q.isNotEmpty() && q.length <= MAX_TEXT) { "Type something to search for" }
            it.respondCode(200, onRequest(Request.Search(q)))
        }
        s.start()
        server = s
        url = s.getAddress().takeIf { it.isNotBlank() }?.let { "http://$it:${s.port}/#$token" }
        Logger.i(TAG, "Companion server on port ${s.port}")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        url = null
    }

    private fun api(s: ManagedHttpServer, method: String, path: String, handler: (HttpContext) -> Unit) {
        s.addHandler(HttpFunctionHandler(method, path) { ctx ->
            if (!authorized(ctx.getHeader("authorization"))) {
                ctx.skipBody(); return@HttpFunctionHandler ctx.respondCode(401, "Scan the code on your TV again")
            }
            if (ctx.contentLength > MAX_BODY) {
                ctx.skipBody(); return@HttpFunctionHandler ctx.respondCode(413, "Too large")
            }
            lastRequestAt = System.currentTimeMillis()
            try {
                handler(ctx)
            } catch (e: IllegalArgumentException) {
                ctx.respondCode(400, e.message ?: "Bad request")
            } catch (e: Throwable) {
                Logger.e(TAG, "Companion request failed: $path", e)
                ctx.respondCode(500, "Something went wrong on the TV")
            }
        }.withHeader("Cache-Control", "no-store"))
    }

    private fun authorized(header: String?): Boolean {
        val presented = header?.removePrefix("Bearer ")?.toByteArray() ?: return false
        return MessageDigest.isEqual(presented, tokenBytes)
    }

    private inline fun <reified T> body(ctx: HttpContext): T = try {
        json.decodeFromString<T>(ctx.readContentString())
    } catch (e: Exception) {
        throw IllegalArgumentException("Bad request")
    }

    private fun page(): String = StateAssets.readAsset(context, "tv/companion.html") ?: "Missing companion page"

    companion object {
        private const val TAG = "TvCompanionServer"
        private const val MAX_BODY = 8 * 1024
        private const val MAX_TEXT = 200

        fun httpUrl(raw: String): String {
            val u = raw.trim().replaceFirst(Regex("^vfuto://", RegexOption.IGNORE_CASE), "https://")
            val uri = try { java.net.URI(u) } catch (e: Exception) { throw IllegalArgumentException("Not a link") }
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && u.length <= 2048) { "Not a web link" }
            return u
        }

        fun decode(v: String?): String = java.net.URLDecoder.decode(v ?: "", "UTF-8")
    }
}
