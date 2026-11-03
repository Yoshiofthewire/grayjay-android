package com.futo.platformplayer.tv

import android.content.Context
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class TvCompanionServerTests {
    private val requests = mutableListOf<TvCompanionServer.Request>()
    private lateinit var server: TvCompanionServer

    @Before
    fun setUp() {
        server = TvCompanionServer(Mockito.mock(Context::class.java), InetAddress.getLoopbackAddress()) { requests.add(it); "ok" }
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun post(path: String, body: String, auth: String?): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        auth?.let { c.setRequestProperty("Authorization", it) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return code to text
    }

    @Test
    fun rejectsMissingToken() {
        assertEquals(401, post("/api/search", """{"query":"cats"}""", null).first)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun rejectsWrongToken() {
        val wrong = "0".repeat(server.token.length)
        assertNotEquals(server.token, wrong)
        assertEquals(401, post("/api/search", """{"query":"cats"}""", "Bearer $wrong").first)
        assertEquals(401, post("/api/plugin", """{"url":"https://example.com/config.json"}""", "Bearer ${server.token.dropLast(1)}").first)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun acceptsTokenAndForwardsRequest() {
        val (code, text) = post("/api/search", """{"query":" cats "}""", "Bearer ${server.token}")
        assertEquals(200, code)
        assertEquals("ok", text)
        assertEquals(listOf(TvCompanionServer.Request.Search("cats")), requests)
    }

    @Test
    fun rejectsNonWebPluginLinks() {
        assertEquals(400, post("/api/plugin", """{"url":"file:///data/x.js"}""", "Bearer ${server.token}").first)
        assertEquals(400, post("/api/plugin", """{"url":"javascript:alert(1)"}""", "Bearer ${server.token}").first)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun rejectsOversizedBodies() {
        val big = """{"query":"${"a".repeat(20_000)}"}"""
        assertEquals(413, post("/api/search", big, "Bearer ${server.token}").first)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun listensOnlyOnTheBoundAddress() {
        assertTrue(server.url!!.startsWith("http://127.0.0.1:${server.port}/#"))
        // Bound to loopback here, so the machine's LAN address must refuse the connection.
        val lan = TvCompanionServer.lanAddress()
        Assume.assumeNotNull(lan) // skipped, not passed, on machines without a LAN address
        assertThrows(java.net.ConnectException::class.java) { java.net.Socket(lan!!, server.port).close() }
    }

    private fun postBytes(path: String, body: ByteArray, auth: String?): Int {
        val c = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setFixedLengthStreamingMode(body.size)
        auth?.let { c.setRequestProperty("Authorization", it) }
        val code = try { c.outputStream.use { it.write(body) }; c.responseCode } catch (e: java.io.IOException) { c.responseCode }
        c.disconnect()
        return code
    }

    @Test
    fun photoEndpointNeedsTokenAndForwardsTheImage() {
        val jpeg = ByteArray(50_000) { (it % 251).toByte() }
        assertEquals(401, postBytes("/api/plugin-photo", jpeg, null))
        assertTrue(requests.isEmpty())
        assertEquals(200, postBytes("/api/plugin-photo", jpeg, "Bearer ${server.token}"))
        val r = requests.single() as TvCompanionServer.Request.InstallPluginFromPhoto
        assertTrue(r.image.contentEquals(jpeg))
    }

    @Test
    fun photoEndpointRefusesOversizedImages() {
        val big = ByteArray(TvCompanionServer.MAX_PHOTO + 1)
        assertEquals(413, postBytes("/api/plugin-photo", big, "Bearer ${server.token}"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun tokensDifferPerSession() {
        val other = TvCompanionServer(Mockito.mock(Context::class.java), InetAddress.getLoopbackAddress()) { "ok" }
        assertNotEquals(server.token, other.token)
        assertEquals(32, server.token.length)
    }

    @Test
    fun httpUrlNormalizesAndValidates() {
        assertEquals("https://plugins.grayjay.app/x/config.json", TvCompanionServer.httpUrl(" vfuto://plugins.grayjay.app/x/config.json "))
        assertEquals("http://192.168.1.5/c.json", TvCompanionServer.httpUrl("http://192.168.1.5/c.json"))
        assertThrows(IllegalArgumentException::class.java) { TvCompanionServer.httpUrl("ftp://example.com/a") }
        assertThrows(IllegalArgumentException::class.java) { TvCompanionServer.httpUrl("not a url") }
        assertThrows(IllegalArgumentException::class.java) { TvCompanionServer.httpUrl("https://") }
    }

    @Test
    fun interleaveRoundRobins() {
        assertEquals(listOf(1, 10, 2, 20, 3), TvHomeScreen.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
        assertEquals(listOf<Int>(), TvHomeScreen.interleave(listOf<List<Int>>()))
    }
}
