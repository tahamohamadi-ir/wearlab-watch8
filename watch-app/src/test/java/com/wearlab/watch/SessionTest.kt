package com.wearlab.watch

import com.wearlab.watch.session.SessionId
import com.wearlab.watch.session.SessionState
import com.wearlab.watch.session.SessionState.*
import com.wearlab.watch.storage.CsvStreamWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SessionTest {
    @Test
    fun sessionIdFormat() {
        // 2026-10-05T14:22:30Z
        assertEquals("T001_20261005_142230_BASELINE_001", SessionId.make("T001", 1791210150000L, "BASELINE", 1))
    }

    @Test
    fun happyPathAndIllegalTransitions() {
        val path = listOf(IDLE, CONFIGURED, STARTING, RUNNING, STOPPING, FINALIZING, COMPLETED)
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a>$b", a.canMoveTo(b)) }
        assertFalse(RUNNING.canMoveTo(COMPLETED))
        assertFalse(IDLE.canMoveTo(RUNNING))
        for (s in SessionState.values()) assertTrue(s.canMoveTo(ERROR))
    }

    @Test
    fun csvWriterCountsAndChecksumMatchFileBytes() {
        val f = File.createTempFile("acc", ".csv")
        val w = CsvStreamWriter(f, listOf("x", "y"))
        w.write(0, 0, 10, 11, 1_000, 0, 3, doubleArrayOf(1.0, Double.NaN))
        w.write(0, 1, 20, 21, 2_000, 1_000, null, doubleArrayOf(2.0, 3.0))
        w.close()
        assertEquals(2, w.samples)
        assertEquals(1_000, w.firstUtcNs)
        assertEquals(2_000, w.lastUtcNs)
        val expected = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, w.sha256)
        assertEquals(listOf("0,0,10,11,1000,0,3,1.0,", "0,1,20,21,2000,1000,,2.0,3.0"), f.readLines().drop(1))
        val (rows, sha) = CsvStreamWriter.scan(f)
        assertEquals(2, rows)
        assertEquals(expected, sha)
    }
}
