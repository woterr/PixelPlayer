package com.theveloper.pixelplay.presentation.components

import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSheetLogicTest {

    @Test
    fun sanitizeSyncedWords_removesLeadingTags_preventsOverlap() {
        val words = listOf(
            SyncedWord(time = 0, word = "v1:"),
            SyncedWord(time = 120, word = "Hello"),
            SyncedWord(time = 240, word = "world")
        )

        val sanitized = sanitizeSyncedWords(words)

        assertEquals(listOf("Hello", "world"), sanitized.map { it.word })
        assertEquals(listOf(120, 240), sanitized.map { it.time })
        assertTrue(sanitized.all { it.startsNewWord })
    }

    @Test
    fun highlightSnapOffsetPx_alignsLineWithHighlightZone() {
        val viewportHeight = 960
        val itemSize = 120
        val highlightOffsetPx = 80f

        val offset = highlightSnapOffsetPx(viewportHeight, itemSize, highlightOffsetPx)

        val expectedCenter = viewportHeight / 2f - highlightOffsetPx
        assertEquals(expectedCenter - itemSize / 2f, offset.toFloat(), 0.5f)
    }

    @Test
    fun highlightSnapOffsetPx_clampsWithinViewportForEndOfList() {
        val viewportHeight = 420
        val itemSize = 320
        val highlightOffsetPx = -160f

        val offset = highlightSnapOffsetPx(viewportHeight, itemSize, highlightOffsetPx)
        val center = offset + itemSize / 2f

        assertTrue(center <= viewportHeight.toFloat())
        assertTrue(center >= itemSize / 2f)
    }

    @Test
    fun highlightSnapOffsetPx_handlesOversizedItems() {
        val viewportHeight = 200
        val itemSize = 260

        val offset = highlightSnapOffsetPx(viewportHeight, itemSize, highlightOffsetPx = 60f)

        assertEquals(0, offset)
    }

    @Test
    fun calculateHighlightMetrics_reservesBottomSpace() {
        val metrics = calculateHighlightMetrics(
            containerHeight = 480.dp,
            highlightZoneFraction = 0.22f,
            highlightOffset = 48.dp
        )

        assertTrue(metrics.bottomPadding > 0.dp)
        assertTrue(metrics.topPadding >= 0.dp)
        assertTrue(metrics.zoneHeight > 0.dp)
    }

    @Test
    fun sanitizeLyricLineText_stripsLrcTimestampTags() {
        val raw = "[00:26.42][01:12.34] Three in the morning, I ain't slept all weekend"

        val sanitized = sanitizeLyricLineText(raw)

        assertEquals("Three in the morning, I ain't slept all weekend", sanitized)
    }

    @Test
    fun normalizeWordEndTime_guaranteesForwardRangeWhenTimestampsMatch() {
        val endTime = normalizeWordEndTime(
            currentWordTimeMs = 2_000L,
            nextWordTimeMs = 2_000L,
            lineEndTimeMs = 2_800L
        )

        assertEquals(2_001L, endTime)
    }

    @Test
    fun normalizeWordEndTime_clampsToLineEnd() {
        val endTime = normalizeWordEndTime(
            currentWordTimeMs = 5_000L,
            nextWordTimeMs = 8_000L,
            lineEndTimeMs = 5_400L
        )

        assertEquals(5_400L, endTime)
    }

    @Test
    fun resolveLineEndTimeMs_extendsPastNextLineWhenLastWordStartsLater() {
        val line = SyncedLine(
            time = 1_000,
            line = "abc",
            words = listOf(
                SyncedWord(1_000, "a"),
                SyncedWord(1_600, "b"),
                SyncedWord(2_000, "c")
            )
        )

        val lineEnd = resolveLineEndTimeMs(line, nextLineStartMs = 2_000)

        assertEquals(2_001L, lineEnd)
    }

    @Test
    fun sanitizeSyncedWords_promotesFirstVisibleWordAfterLeadingMarker() {
        val words = listOf(
            SyncedWord(time = 1000, word = "v1:"),
            SyncedWord(time = 1200, word = "fall", startsNewWord = false)
        )

        val sanitized = sanitizeSyncedWords(words)

        assertEquals(1, sanitized.size)
        assertEquals("fall", sanitized[0].word)
        assertTrue(sanitized[0].startsNewWord)
    }

    @Test
    fun clusterSyncedWords_keepsSyllablesInsideSameWord() {
        val clusters = clusterSyncedWords(
            listOf(
                SyncedWord(time = 1000, word = "to", startsNewWord = true),
                SyncedWord(time = 1100, word = "geth", startsNewWord = false),
                SyncedWord(time = 1200, word = "er", startsNewWord = false),
                SyncedWord(time = 1500, word = "now", startsNewWord = true)
            )
        )

        assertEquals(2, clusters.size)
        assertEquals(listOf("to", "geth", "er"), clusters[0].words.map { it.word })
        assertEquals(listOf("now"), clusters[1].words.map { it.word })
        assertEquals(0, clusters[0].startIndex)
        assertEquals(3, clusters[1].startIndex)
    }

    @Test
    fun resolveHighlightedWordIndex_picksLastVisibleWordAtLineEnd() {
        val words = listOf(
            SyncedWord(time = 10_000, word = "fall"),
            SyncedWord(time = 10_250, word = "in"),
            SyncedWord(time = 10_500, word = "love")
        )

        val idx = resolveHighlightedWordIndex(
            words = words,
            positionMs = 10_900,
            lineStartTimeMs = 10_000,
            lineEndTimeMs = 11_000
        )

        assertEquals(2, idx)
    }

    @Test
    fun resolveSeekPositionMs_subtractsPositiveLyricsOffset() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 12_000L,
            lyricsSyncOffsetMs = 750
        )

        assertEquals(11_250L, seekPosition)
    }

    @Test
    fun resolveSeekPositionMs_addsNegativeLyricsOffset() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 12_000L,
            lyricsSyncOffsetMs = -750
        )

        assertEquals(12_750L, seekPosition)
    }

    @Test
    fun resolveSeekPositionMs_clampsToZeroWhenOffsetWouldGoNegative() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 300L,
            lyricsSyncOffsetMs = 750
        )

        assertEquals(0L, seekPosition)
    }

    @Test
    fun resolveWordFillProgress_staysEmptyBeforeWordStarts() {
        val progress = resolveWordFillProgress(
            positionMs = 9_900,
            wordStartMs = 10_000,
            wordDurationMs = 400,
            fallbackEndMs = 10_400
        )

        assertEquals(0f, progress, 0.0001f)
    }

    @Test
    fun resolveWordFillProgress_advancesInsideSyllableDuration() {
        val halfway = resolveWordFillProgress(
            positionMs = 10_200,
            wordStartMs = 10_000,
            wordDurationMs = 400,
            fallbackEndMs = 10_400
        )

        assertTrue(halfway > 0.3f && halfway < 0.8f)
        assertEquals(
            1f,
            resolveWordFillProgress(10_400, 10_000, 400, 10_400),
            0.0001f
        )
    }

    @Test
    fun resolveWordFillProgress_usesNextWordForLegacyLrc() {
        val progress = resolveWordFillProgress(
            positionMs = 10_250,
            wordStartMs = 10_000,
            wordDurationMs = 0,
            fallbackEndMs = 10_500
        )

        assertTrue(progress > 0.3f && progress < 0.9f)
    }

    @Test
    fun resolveWordFillProgress_capsLegacyFillOnLongGaps() {
        // A word stamped 10 s before the next line must not crawl for 10 s.
        val afterCap = resolveWordFillProgress(
            positionMs = 12_000,
            wordStartMs = 10_000,
            wordDurationMs = 0,
            fallbackEndMs = 20_000
        )

        assertEquals(1f, afterCap, 0.0001f)
    }

    @Test
    fun resolveLineFillFractions_completesFirstLineBeforeSecond() {
        val lineCounts = listOf(10, 10)

        val quarter = resolveLineFillFractions(lineCounts, 0.25f)
        assertEquals(0.5f, quarter[0], 0.0001f)
        assertEquals(0f, quarter[1], 0.0001f)

        val halfway = resolveLineFillFractions(lineCounts, 0.5f)
        assertEquals(1f, halfway[0], 0.0001f)
        assertEquals(0f, halfway[1], 0.0001f)

        val threeQuarters = resolveLineFillFractions(lineCounts, 0.75f)
        assertEquals(1f, threeQuarters[0], 0.0001f)
        assertEquals(0.5f, threeQuarters[1], 0.0001f)
    }

    @Test
    fun resolveLineFillFractions_weightsLinesByCharacterCount() {
        val fractions = resolveLineFillFractions(lineCharCounts = listOf(5, 15), progress = 0.5f)

        assertEquals(1f, fractions[0], 0.0001f)
        assertEquals(1f / 3f, fractions[1], 0.0001f)
    }

    @Test
    fun resolveLineFillFractions_handlesEmptyAndOutOfRangeProgress() {
        assertTrue(resolveLineFillFractions(emptyList(), 0.5f).isEmpty())
        assertEquals(listOf(0f, 0f), resolveLineFillFractions(listOf(0, 0), 0.5f))
        assertEquals(
            listOf(1f, 1f),
            resolveLineFillFractions(listOf(10, 10), 1.5f)
        )
    }

    @Test
    fun splitMultiTokenWords_splitsPhraseSpanIntoProportionalWords() {
        // A provider can time the whole phrase as one span; each word must get
        // its own start so "And it comes" lights up before "so far" is sung.
        val span = SyncedWord(time = 10_000, word = "And it comes so far")

        val words = splitMultiTokenWords(listOf(span), lineEndTimeMs = 15_000)

        assertEquals(
            listOf("And", "it", "comes", "so", "far"),
            words.map { it.word }
        )
        assertTrue(words.all { it.startsNewWord })
        assertEquals(10_000, words[0].time)
        // Time is distributed by token length across the 5 s span.
        assertEquals(11_000, words[1].time)
        assertEquals(11_666, words[2].time)
        assertEquals(13_333, words[3].time)
        assertEquals(14_000, words[4].time)
        assertEquals(1_000, words[0].durationMs)
        assertEquals(666, words[1].durationMs)
        assertEquals(1_667, words[2].durationMs)
        assertEquals(667, words[3].durationMs)
        assertEquals(1_000, words[4].durationMs)
    }

    @Test
    fun splitMultiTokenWords_highlightsOnlySungWordsAsPhraseProgresses() {
        val span = SyncedWord(time = 10_000, word = "And it comes so far")
        val words = splitMultiTokenWords(listOf(span), lineEndTimeMs = 15_000)

        // At 11.2 s the singer is inside "it comes"; everything before and
        // including "it" is already part of the accent prefix.
        val index = resolveHighlightedWordIndex(
            words = words,
            positionMs = 11_200,
            lineStartTimeMs = 10_000,
            lineEndTimeMs = 15_000
        )

        assertEquals(1, index)
        assertEquals(
            1f,
            resolveWordFillProgress(11_200, words[0].time.toLong(), words[0].durationMs.toLong(), words[1].time.toLong()),
            0.0001f
        )
        assertTrue(
            resolveWordFillProgress(11_200, words[1].time.toLong(), words[1].durationMs.toLong(), words[2].time.toLong()) in 0f..1f
        )
        assertEquals(
            0f,
            resolveWordFillProgress(11_200, words[2].time.toLong(), words[2].durationMs.toLong(), words[3].time.toLong()),
            0.0001f
        )
    }

    @Test
    fun splitMultiTokenWords_keepsSingleAndSyllableWordsUntouched() {
        val original = listOf(
            SyncedWord(time = 1_000, word = "Hello"),
            SyncedWord(time = 1_200, word = "world", durationMs = 300)
        )

        val result = splitMultiTokenWords(original, lineEndTimeMs = 2_000)

        assertEquals(original, result)
    }

    @Test
    fun splitMultiTokenWords_stopsAtNextWordStart() {
        val words = listOf(
            SyncedWord(time = 1_000, word = "And it comes"),
            SyncedWord(time = 1_600, word = "soon")
        )

        val split = splitMultiTokenWords(words, lineEndTimeMs = 3_000)

        assertEquals(listOf("And", "it", "comes", "soon"), split.map { it.word })
        assertEquals(1_000, split[0].time)
        assertEquals(1_180, split[1].time)
        assertEquals(1_300, split[2].time)
        // The span cannot borrow time from the following word.
        assertEquals(1_600, split[2].time + split[2].durationMs)
        assertEquals(1_600, split[3].time)
    }

    @Test
    fun splitMultiTokenWords_usesDeclaredDurationAndHandlesEmptyInput() {
        val span = SyncedWord(time = 5_000, word = "come back", durationMs = 400)

        val split = splitMultiTokenWords(listOf(span), lineEndTimeMs = 90_000)

        assertEquals(listOf("come", "back"), split.map { it.word })
        assertEquals(5_000, split[0].time)
        assertEquals(5_200, split[1].time)
        assertEquals(5_400, split[1].time + split[1].durationMs)
        assertTrue(splitMultiTokenWords(emptyList(), 1_000).isEmpty())
    }

    @Test
    fun resolveLineSweepDurationMs_sweepsAcrossRowSpanImpliedByNextRow() {
        // A row's end is never stored: the next row's start bounds the sweep.
        assertEquals(3_500L, resolveLineSweepDurationMs(10_000L, 13_500L, isLastLine = false))
    }

    @Test
    fun resolveLineSweepDurationMs_finalRowHoldsDefaultInsteadOfUnboundedSpan() {
        // The final row has no follower (next start = Int.MAX_VALUE sentinel).
        assertEquals(
            4_500L,
            resolveLineSweepDurationMs(10_000L, Int.MAX_VALUE.toLong(), isLastLine = true)
        )
    }

    @Test
    fun resolveLineSweepDurationMs_stopsCrawlingAcrossInstrumentalGaps() {
        // A row followed by a 50 s instrumental gap must complete, not crawl.
        assertEquals(8_000L, resolveLineSweepDurationMs(10_000L, 60_000L, isLastLine = false))
    }

    @Test
    fun resolveLineSweepDurationMs_neverRunsBackwards() {
        assertEquals(0L, resolveLineSweepDurationMs(10_000L, 9_000L, isLastLine = false))
        assertEquals(0L, resolveLineSweepDurationMs(5_000L, 5_000L, isLastLine = false))
    }

    @Test
    fun lineSweepProgress_advancesAcrossWholeRowSpan() {
        val duration = resolveLineSweepDurationMs(10_000L, 14_000L, isLastLine = false)

        val halfway = resolveWordFillProgress(
            positionMs = 12_000L,
            wordStartMs = 10_000L,
            wordDurationMs = duration,
            fallbackEndMs = 14_000L
        )

        // Same mild ease-out as syllable fills: at linear halfway the accent
        // already covers ~62% — a sung line attacks fast and sustains.
        assertTrue(halfway > 0.6f && halfway < 0.65f)
        assertEquals(
            1f,
            resolveWordFillProgress(14_001L, 10_000L, duration, 14_000L),
            0.0001f
        )
        assertEquals(
            0f,
            resolveWordFillProgress(9_999L, 10_000L, duration, 14_000L),
            0.0001f
        )
    }
}
