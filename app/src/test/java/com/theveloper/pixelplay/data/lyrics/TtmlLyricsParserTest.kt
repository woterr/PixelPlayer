package com.theveloper.pixelplay.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlLyricsParserTest {

    @Test
    fun parse_keepsWordDurationsForKaraokeFill() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:00:10.000" end="00:00:12.000">
                    <span begin="00:00:10.000" end="00:00:10.500">Hello</span>
                    <span begin="00:00:10.600" end="00:00:11.200">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = TtmlLyricsParser.parse(ttml)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals(10_000, line.time)
        assertEquals("Hello world", line.line)
        val words = requireNotNull(line.words)
        assertEquals(listOf("Hello", "world"), words.map { it.word })
        assertEquals(listOf(10_000, 10_600), words.map { it.time })
        assertEquals(listOf(500, 600), words.map { it.durationMs })
    }

    @Test
    fun parse_mergesSyllableSpansIntoDisplayWords() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="1.0" end="2.0"><span begin="1.0" end="1.3">Hel</span><span begin="1.3" end="1.7">lo</span> <span begin="1.8" end="2.0">you</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val words = requireNotNull(TtmlLyricsParser.parse(ttml).single().words)

        assertEquals(listOf("Hello", "you"), words.map { it.word })
        assertEquals(1_000, words[0].time)
        assertEquals(700, words[0].durationMs)
    }

    @Test
    fun parse_skipsTranslationAndBackgroundTracks() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body><div>
                <p begin="5.0" end="6.0">
                  <span begin="5.0" end="5.4">Main</span>
                  <span ttm:role="x-translation" begin="5.0" end="5.4">Traducción</span>
                  <span ttm:role="x-bg" begin="5.5" end="6.0">(echo)</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val line = TtmlLyricsParser.parse(ttml).single()

        assertEquals("Main", line.line)
        assertEquals(listOf("Main"), requireNotNull(line.words).map { it.word })
    }

    @Test
    fun parse_appliesLyricOffsetAndClampsToZero() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                ttm:lyricOffset="-4.0">
              <body><div>
                <p begin="5.0" end="6.0"><span begin="5.0" end="5.5">Late</span></p>
              </div></body>
            </tt>
        """.trimIndent()

        val line = TtmlLyricsParser.parse(ttml).single()

        assertEquals(1_000, line.time)
        assertEquals(1_000, requireNotNull(line.words).single().time)
    }

    @Test
    fun parseTime_supportsSecondsMinutesAndMillisSuffix() {
        assertEquals(12_500L, TtmlLyricsParser.parseTime("12.5"))
        assertEquals(12_500L, TtmlLyricsParser.parseTime("00:00:12.500"))
        assertEquals(90_000L, TtmlLyricsParser.parseTime("01:30"))
        assertEquals(1_500L, TtmlLyricsParser.parseTime("1500ms"))
        assertEquals(2_000L, TtmlLyricsParser.parseTime("2s"))
    }

    @Test
    fun parse_returnsEmptyForMalformedDocument() {
        assertTrue(TtmlLyricsParser.parse("not ttml at all").isEmpty())
        assertTrue(TtmlLyricsParser.parse("<tt><body><p begin='oops'>x</p></body></tt>").isEmpty())
    }
}
