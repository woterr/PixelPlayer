package com.theveloper.pixelplay.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAudioDurationUtilsTest {

    @Test
    fun `resolveEffectiveDurationMs prefers the first positive candidate`() {
        assertEquals(42_000L, resolveEffectiveDurationMs(0L, null, 42_000L))
        assertEquals(42_000L, resolveEffectiveDurationMs(42_000L, 99_000L))
    }

    @Test
    fun `resolveEffectiveDurationMs treats non-positive values as unknown`() {
        assertEquals(0L, resolveEffectiveDurationMs(0L, -1L, null))
    }

    @Test
    fun `unknown durations are never below the minimum`() {
        assertFalse(isBelowMinimumDuration(0L, 10_000))
        assertFalse(isBelowMinimumDuration(-5L, 10_000))
    }

    @Test
    fun `known durations are filtered against the minimum`() {
        assertTrue(isBelowMinimumDuration(9_999L, 10_000))
        assertFalse(isBelowMinimumDuration(10_000L, 10_000))
        assertFalse(isBelowMinimumDuration(10_001L, 10_000))
    }

    @Test
    fun `disabled minimum duration keeps every known duration`() {
        assertFalse(isBelowMinimumDuration(1L, 0))
        assertFalse(isBelowMinimumDuration(1L, -100))
    }

    @Test
    fun `known MediaStore duration must match the stored value`() {
        assertTrue(isStoredDurationCurrent(240_000L, 240_000L))
        assertFalse(isStoredDurationCurrent(240_000L, 239_999L))
        assertFalse(isStoredDurationCurrent(240_000L, 0L))
    }

    @Test
    fun `unknown MediaStore duration only retries until recovery succeeds`() {
        // Already recovered from the file: treat as current, do not reprocess every sync.
        assertTrue(isStoredDurationCurrent(0L, 240_000L))
        // Not recovered yet: reprocess so a stored 0 can be repaired.
        assertFalse(isStoredDurationCurrent(0L, 0L))
        assertFalse(isStoredDurationCurrent(-1L, 0L))
    }
}
