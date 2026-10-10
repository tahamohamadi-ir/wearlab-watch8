package com.wearlab.watch.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Append-only raw stream file (ADR-001). SHA-256 is computed over exactly the bytes written, so the
 * manifest checksum can be verified after transfer (M13). No interpolation, smoothing or filtering.
 */
class CsvStreamWriter(val file: File, channels: List<String>) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val out = BufferedOutputStream(DigestOutputStream(FileOutputStream(file, false), digest), 64 * 1024)
    private val sb = StringBuilder(160)

    var samples = 0L; private set
    var firstUtcNs = 0L; private set
    var lastUtcNs = 0L; private set
    var sha256: String? = null; private set

    init {
        out.write(("window,sequence,source_ts_ns,monotonic_ns,utc_ns,session_offset_ns,quality," + channels.joinToString(",") + "\n").toByteArray())
    }

    fun write(window: Int, seq: Long, sourceTsNs: Long, monoNs: Long, utcNs: Long, offsetNs: Long, quality: Int?, values: DoubleArray) {
        sb.setLength(0)
        sb.append(window).append(',').append(seq).append(',').append(sourceTsNs).append(',').append(monoNs).append(',')
            .append(utcNs).append(',').append(offsetNs).append(',').append(quality ?: "")
        for (v in values) {
            sb.append(',')
            if (!v.isNaN()) sb.append(v)
        }
        sb.append('\n')
        out.write(sb.toString().toByteArray())
        if (samples == 0L) firstUtcNs = utcNs
        lastUtcNs = utcNs
        samples++
    }

    fun flush() = out.flush()

    fun close() {
        out.flush(); out.close()
        sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Used for recovery of interrupted sessions: row count (excluding header) and checksum of the file as it is. */
        fun scan(file: File): Pair<Long, String> {
            val md = MessageDigest.getInstance("SHA-256")
            var lines = 0L
            file.inputStream().buffered().use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    for (k in 0 until n) if (buf[k] == '\n'.code.toByte()) lines++
                }
            }
            return maxOf(0L, lines - 1) to md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
