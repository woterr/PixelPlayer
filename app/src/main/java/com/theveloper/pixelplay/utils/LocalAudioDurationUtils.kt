package com.theveloper.pixelplay.utils

/**
 * Picks the first usable (strictly positive) duration from the supplied candidates.
 *
 * MediaStore can report `0` (or `NULL`, surfaced as `0` by the cursor) for perfectly valid
 * files — most notably ALAC/M4A on some Samsung devices. Callers should treat `0` as
 * "unknown" rather than "zero-length", otherwise those files get filtered out of the library.
 */
fun resolveEffectiveDurationMs(vararg candidates: Long?): Long =
    candidates.firstOrNull { it != null && it > 0L } ?: 0L

/**
 * Whether a *known* duration should be filtered out by the user's minimum song duration.
 *
 * Unknown durations (`0`) must never be filtered here: the whole point of tracking them is to
 * keep files whose MediaStore metadata is incomplete. They are still subject to the user's
 * preference once a real duration has been recovered from the file itself.
 */
fun isBelowMinimumDuration(durationMs: Long, minDurationMs: Int): Boolean {
    if (durationMs <= 0L) return false
    val floor = minDurationMs.coerceAtLeast(0).toLong()
    return durationMs < floor
}

/**
 * Whether the duration already stored in the library matches a fresh MediaStore row.
 *
 * When MediaStore supplies a known duration it must match exactly. When MediaStore supplies
 * `0` ("unknown"), a positive stored duration means the file was already recovered and does
 * not need to be read again; a stored `0` means recovery has not succeeded yet and should be
 * retried on the next sync.
 */
fun isStoredDurationCurrent(mediaStoreDurationMs: Long, storedDurationMs: Long): Boolean =
    if (mediaStoreDurationMs > 0L) {
        storedDurationMs == mediaStoreDurationMs
    } else {
        storedDurationMs > 0L
    }
