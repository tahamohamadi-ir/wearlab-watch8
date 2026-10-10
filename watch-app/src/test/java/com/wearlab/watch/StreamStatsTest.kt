package com.wearlab.watch

import com.wearlab.watch.acquisition.StreamStats
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTest {
    private val ms = 1_000_000L

    @Test
    fun steadyStreamHasExpectedRateAndNoGaps() {
        val s = StreamStats()
        for (i in 0 until 101) s.record(i * 20 * ms) // 50 Hz
        val r = s.snapshot()
        assertEquals(101, r.samples)
        assertEquals(50.0, r.observedRateHz, 0.01)
        assertEquals(20.0, r.meanDtMs, 0.001)
        assertEquals(0.0, r.stdDtMs, 0.001)
        assertEquals(0, r.gaps)
    }

    @Test
    fun longIntervalIsCountedAsGap() {
        val s = StreamStats()
        var t = 0L
        repeat(20) { s.record(t); t += 20 * ms }
        t += 200 * ms
        s.record(t)
        val r = s.snapshot()
        assertEquals(1, r.gaps)
        assertEquals(220.0, r.maxDtMs, 0.001)
    }

    @Test
    fun duplicateTimestampsDoNotDefineAnInterval() {
        val s = StreamStats()
        s.record(0); s.record(0); s.record(40 * ms)
        assertEquals(40.0, s.snapshot().meanDtMs, 0.001)
    }

    @Test
    fun sequenceDiscontinuityCountsDrops() {
        val s = StreamStats()
        s.record(0, 1); s.record(20 * ms, 2); s.record(40 * ms, 6)
        assertEquals(3, s.snapshot().droppedBySeq)
    }
}
