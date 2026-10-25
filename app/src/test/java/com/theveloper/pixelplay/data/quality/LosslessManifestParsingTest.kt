package com.theveloper.pixelplay.data.quality

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.addon.AddonClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

class LosslessManifestParsingTest {

    @Test
    fun `sample rate comes from manifest audioSamplingRate attribute`() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><AdaptationSet><Representation
            | id="0" audioSamplingRate="96000" mimeType="audio/mp4"/> """.trimMargin()
        val api = newApi()
        assertThat(api.manifestSampleRateOf(xml)).isEqualTo(96_000)
        assertThat(api.manifestSampleRateOf("<MPD><Representation audioSamplingRate=\"44100\"/></MPD>"))
            .isEqualTo(44_100)
        assertThat(api.manifestSampleRateOf("<MPD/>")).isNull()
    }

    @Test
    fun `bit depth comes from rendition id ground truth`() {
        val api = newApi()
        val xml = """<MPD><Representation id="FLAC_HIRES,48000,24" codecs="flac"/></MPD>"""
        assertThat(api.manifestBitDepthOf(xml)).isEqualTo(24)
        assertThat(api.manifestBitDepthOf("<MPD><Representation id=\"FLAC,44100,16\"/></MPD>")).isEqualTo(16)
        assertThat(api.manifestBitDepthOf("<MPD><Representation id=\"tmpf\"/></MPD>")).isNull()
    }

    @Test
    fun `atmos and spatial manifest detection`() {
        val api = newApi()
        assertThat(api.isAtmosManifest("""<MPD><Representation codecs="ec-3"/></MPD>""")).isTrue()
        assertThat(api.isAtmosManifest("""<MPD><Representation codecs="flac"/></MPD>""")).isFalse()
        assertThat(api.isSpatialManifest("""<MPD><Representation codecs="mha1"/></MPD>""")).isTrue()
        assertThat(api.isSpatialManifest("""<MPD><Representation codecs="flac"/></MPD>""")).isFalse()
    }

    @Test
    fun `codec classification helpers`() {
        val api = newApi()
        assertThat(api.isAtmosCodec("ec-3")).isTrue()
        assertThat(api.isAtmosCodec("EAC3")).isTrue()
        assertThat(api.isAtmosCodec("ac-3")).isTrue()
        assertThat(api.isAtmosCodec("flac")).isFalse()

        assertThat(api.isAtmosStreamUrl("http://cdn/audio.mp4?codecs=ec-3")).isTrue()
        assertThat(api.isAtmosStreamUrl("http://cdn/audio.flac")).isFalse()
    }

    @Test
    fun `decoy prank streams are rejected`() {
        val api = newApi()
        assertThat(api.isDecoyStream("https://pranks-cdn.example/file.flac")).isTrue()
        assertThat(api.isDecoyStream("https://definatelynagato.example/file.flac")).isTrue()
        assertThat(api.isDecoyStream("https://tidal-cdn.example/file.flac")).isFalse()
        assertThat(api.isDecoyStream(null)).isFalse()
    }

    @Test
    fun `normalizeBase strips manifest json and trailing slash`() {
        assertThat(AddonClient.normalizeBase("http://localhost:8787/a/token/"))
            .isEqualTo("http://localhost:8787/a/token")
        assertThat(AddonClient.normalizeBase("http://localhost:8787/a/token/manifest.json"))
            .isEqualTo("http://localhost:8787/a/token")
        assertThat(AddonClient.normalizeBase("  http://host/a/b  ")).isEqualTo("http://host/a/b")
    }

    @Test
    fun `parse depth and rate from quality strings`() {
        val depth = com.theveloper.pixelplay.data.addon.parseDepthFromQualityString("24-BIT / 96k")
        assertThat(depth).isEqualTo(24)
        assertThat(com.theveloper.pixelplay.data.addon.parseDepthFromQualityString("HI_RES")).isEqualTo(24)
        assertThat(com.theveloper.pixelplay.data.addon.parseDepthFromQualityString("LOSSLESS")).isEqualTo(16)
        assertThat(com.theveloper.pixelplay.data.addon.parseDepthFromQualityString(null)).isNull()

        assertThat(com.theveloper.pixelplay.data.addon.extractSampleRateFromElement(JsonPrimitive(48000.0)))
            .isEqualTo(48000.0)
        assertThat(com.theveloper.pixelplay.data.addon.extractSampleRateFromElement(JsonPrimitive("48k")))
            .isEqualTo(48000.0)
        assertThat(com.theveloper.pixelplay.data.addon.extractBitDepthFromElement(JsonPrimitive(24))).isEqualTo(24)
        assertThat(com.theveloper.pixelplay.data.addon.extractBitDepthFromElement(JsonPrimitive(2))).isNull()
    }

    @Test
    fun `parseTitle strips artist dash noise and keeps core`() = runTest {
        val api = newApi()

        val simple = api.parseTitle("Rolling In The Deep", "Adele")
        assertThat(simple.core).isEqualTo("rollinginthedeep")
        assertThat(simple.versions).isEmpty()

        val dash = api.parseTitle("Yalla - Artist Name", "Artist Name")
        assertThat(dash.core).isEqualTo("yalla")
        assertThat(dash.context).isNotEmpty()

        val remix = api.parseTitle("Levitating (Dua Lipa Remix)", "Dua Lipa")
        assertThat(remix.versions).isNotEmpty()
        assertThat(remix.versions.first()).isEqualTo("remix")
    }

    @Test
    fun `extractCoreTitle removes video noise`() {
        val api = newApi()
        assertThat(api.extractCoreTitle("My Heart (Official Music Video)")).isEqualTo("My Heart")
        assertThat(api.extractCoreTitle("Song - Music Video")).isEqualTo("Song")
    }

    private fun newApi(): LosslessMusicApi {
        val tmpDir = Files.createTempDirectory("lossless-manifest-test")
        tmpDir.toFile().deleteOnExit()
        // DataStore needs a scope; a detached one is enough because parsing
        // helpers never read prefs.
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val preferences = com.theveloper.pixelplay.data.preferences.StreamQualityPreferences(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { tmpDir.resolve("streamq.preferences_pb").toFile() }
            ),
            scope = scope,
        )
        return LosslessMusicApi(okHttpClient = OkHttpClient(), preferences = preferences)
    }
}
