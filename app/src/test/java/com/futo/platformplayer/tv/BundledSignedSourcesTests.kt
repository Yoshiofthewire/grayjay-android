package com.futo.platformplayer.tv

import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds only run YouTube when it is FUTO-signed (it needs the Browser package), so the stable
 * build bundles FUTO's published, signed copy. This fails if that copy is altered or re-fetched unsigned.
 */
class BundledSignedSourcesTests {
    @Test
    fun bundledYoutubeIsSignedByFuto() {
        val dir = listOf("src/stable/assets/sources-signed/youtube", "app/src/stable/assets/sources-signed/youtube")
            .map(::File).first { it.isDirectory }
        val config = SourcePluginConfig.fromJson(File(dir, "YoutubeConfig.json").readText(), "")
        val script = File(dir, config.scriptUrl.removePrefix("./")).readText()
        assertTrue("not FUTO's key", config.isOfficialAuthor())
        assertTrue("signature doesn't match the script", config.validate(script))
    }
}
