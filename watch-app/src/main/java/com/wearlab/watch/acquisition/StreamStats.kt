package com.wearlab.watch.acquisition

import kotlin.math.sqrt

data class StreamSnapshot(
    val samples: Long = 0,
    val observedRateHz: Double = 0.0,
    val meanDtMs: Double = 0.0,
    val stdDtMs: Double = 0.0,
    val maxDtMs: Double = 0.0,
    /** Intervals longer than gapFactor x the running mean interval (inside a capture window). */
    val gaps: Long = 0,
    /** Sequence discontinuities (only when the source supplies sequence numbers). */
    val droppedBySeq: Long = 0,
    /** Time actually spent capturing (excludes the waiting between periodic windows). */
    val durationMs: Long = 0,
    val error: String? = null,
)

/** Pure running statistics over source timestamps (no Android deps, unit-tested). */
class StreamStats(private val gapFactor: Double = 3.0) {
    private var n = 0L
    private var winFirst = 0L
    private var lastTs = 0L
    private var activeNs = 0L
    private var windows = 0L
    private var boundary = true
    private var lastSeq = -1L
    private var dtN = 0L
    private var mean = 0.0
    private var m2 = 0.0
    private var maxDt = 0.0
    private var gaps = 0L
    private var seqDrops = 0L

    /** A new capture window starts: the silence since the last sample is a schedule, not a gap. */
    fun boundary() { boundary = true }

    fun record(sourceTsNs: Long, sequence: Long? = null) {
        if (boundary) {
            if (n > 0) activeNs += lastTs - winFirst
            winFirst = sourceTsNs
            windows++
            boundary = false
            lastSeq = -1L
        } else {
            val dtMs = (sourceTsNs - lastTs) / 1e6
            // Batched sources can deliver identical timestamps; those do not define an interval.
            if (dtMs > 0) {
                if (dtN >= 5 && dtMs > gapFactor * mean) gaps++
                dtN++
                val d = dtMs - mean
                mean += d / dtN
                m2 += d * (dtMs - mean)
                if (dtMs > maxDt) maxDt = dtMs
            }
        }
        if (sequence != null) {
            if (lastSeq >= 0 && sequence > lastSeq + 1) seqDrops += sequence - lastSeq - 1
            lastSeq = sequence
        }
        lastTs = sourceTsNs
        n++
    }

    fun snapshot(error: String? = null): StreamSnapshot {
        val activeMs = (activeNs + if (n > 0) lastTs - winFirst else 0) / 1e6
        return StreamSnapshot(
            samples = n,
            observedRateHz = if (n > windows && activeMs > 0) (n - windows) / (activeMs / 1000.0) else 0.0,
            meanDtMs = mean,
            stdDtMs = if (dtN > 1) sqrt(m2 / (dtN - 1)) else 0.0,
            maxDtMs = maxDt,
            gaps = gaps,
            droppedBySeq = seqDrops,
            durationMs = activeMs.toLong(),
            error = error,
        )
    }
}
