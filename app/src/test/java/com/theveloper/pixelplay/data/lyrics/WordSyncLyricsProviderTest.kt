package com.theveloper.pixelplay.data.lyrics

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSyncLyricsProviderTest {

    private val provider = WordSyncLyricsProvider(OkHttpClient())

    @Test
    fun parseKrc_mergesLatinSyllablesIntoDisplayWords() {
        val krc = "[1000,2000]<0,300,0>Hel<300,400,0>lo <1000,500,0>world"

        val line = requireNotNull(provider.parseKrc(krc)).single()

        assertEquals("Hello world", line.line)
        val words = requireNotNull(line.words)
        assertEquals(listOf("Hello", "world"), words.map { it.word })
        assertEquals(listOf(1_000, 2_000), words.map { it.time })
        assertEquals(listOf(700, 500), words.map { it.durationMs })
    }

    @Test
    fun parseKrc_keepsCjkCharactersAsSeparateFills() {
        val krc = "[0,1000]<0,500,0>你<500,500,0>好"

        val line = requireNotNull(provider.parseKrc(krc)).single()

        assertEquals("你好", line.line)
        val words = requireNotNull(line.words)
        assertEquals(listOf("你", "好"), words.map { it.word })
        assertTrue(words.all { it.startsNewWord })
    }

    @Test
    fun parseKrc_appliesGlobalOffsetAndClampsToZero() {
        val krc = "[offset:250]\n[1000,1000]<0,400,0>Hi"
        val line = requireNotNull(provider.parseKrc(krc)).single()

        assertEquals(1_250, line.time)
        assertEquals(1_250, requireNotNull(line.words).single().time)

        val negative = "[offset:-5000]\n[1000,1000]<0,400,0>Hi"
        assertEquals(0, requireNotNull(provider.parseKrc(negative)).single().time)
    }

    @Test
    fun parseKrc_skipsCreditLines() {
        val krc = """
            [0,1000]<0,400,0>Lyrics by: Someone
            [1000,1000]<0,400,0>Real line
        """.trimIndent()

        val lines = requireNotNull(provider.parseKrc(krc))

        assertEquals(listOf("Real line"), lines.map { it.line })
    }
}
