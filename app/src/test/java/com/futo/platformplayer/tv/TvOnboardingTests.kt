package com.futo.platformplayer.tv

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvOnboardingTests {
    @Test
    fun parsesOfficialConfigLinksInPageOrder() {
        val html = """
            <a href="grayjay://plugin/https://plugins.grayjay.app/Youtube/YoutubeConfig.json">Install</a>
            <a href="grayjay://plugin/https://plugins.grayjay.app/Nebula/NebulaConfig.json">Install</a>
            <a href="grayjay://plugin/https://plugins.grayjay.app/Youtube/YoutubeConfig.json">Again</a>
            <a href="grayjay://plugin/https://evil.example/plugins.grayjay.app/X/XConfig.json">Elsewhere</a>
            <a href="https://plugins.grayjay.app/Twitch/TwitchConfig.json">Plain link, not an install link</a>
        """
        assertEquals(
            listOf("https://plugins.grayjay.app/Youtube/YoutubeConfig.json", "https://plugins.grayjay.app/Nebula/NebulaConfig.json"),
            TvSourceCatalog.parseConfigUrls(html)
        )
    }

    @Test
    fun acceptsTheLinkFormsSourcesAreSharedIn() {
        val url = "https://plugins.grayjay.app/Nebula/NebulaConfig.json"
        assertEquals(url, TvCompanionServer.httpUrl("grayjay://plugin/$url"))
        assertEquals(url, TvCompanionServer.httpUrl("vfuto://plugins.grayjay.app/Nebula/NebulaConfig.json"))
        assertEquals(url, TvCompanionServer.httpUrl(" $url "))
    }

    @Test
    fun readsAQrCodeFromPixels() {
        val text = "https://plugins.grayjay.app/Youtube/YoutubeConfig.json"
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(text, TvQr.decodePixels(pixels, m.width, m.height))
    }

    @Test
    fun returnsNullWhenThereIsNoQrCode() {
        assertNull(TvQr.decodePixels(IntArray(200 * 200) { 0xFF808080.toInt() }, 200, 200))
    }
}
