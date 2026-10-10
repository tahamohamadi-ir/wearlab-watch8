package com.wearlab.watch.core.time

import android.os.SystemClock

/** The clocks of ADR-002: monotonic (ordering), UTC (joining with EMA/S9). Session offset comes in Phase 2. */
object Timebase {
    fun monotonicNs(): Long = SystemClock.elapsedRealtimeNanos()
    fun utcNs(): Long = System.currentTimeMillis() * 1_000_000L

    /** UTC equivalent of a monotonic-clock time. */
    fun monoToUtcNs(monoNs: Long, nowMonoNs: Long = monotonicNs(), nowUtcNs: Long = utcNs()): Long =
        nowUtcNs - (nowMonoNs - monoNs)

    /** Monotonic equivalent of a UTC time. */
    fun utcToMonoNs(utcNs: Long, nowMonoNs: Long = monotonicNs(), nowUtcNs: Long = utcNs()): Long =
        nowMonoNs - (nowUtcNs - utcNs)
}
