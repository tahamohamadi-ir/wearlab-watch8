package com.wearlab.watch

import com.wearlab.watch.study.*
import org.junit.Assert.*
import org.junit.Test

class TasksTest {
    @Test
    fun stroopHalfCongruentAndNeverWordEqualsInkWhenIncongruent() {
        val g = StroopGenerator(7)
        val trials = List(4000) { g.next() }
        val share = trials.count { it.congruent } / 4000.0
        assertEquals(0.5, share, 0.03)
        assertTrue(trials.filter { !it.congruent }.all { it.word != it.ink })
    }

    @Test
    fun stroopSeedReproducesTheSameSequence() {
        assertEquals(List(50) { StroopGenerator(3).next() }.first(), StroopGenerator(3).next())
        val a = StroopGenerator(11); val b = StroopGenerator(11)
        assertEquals(List(200) { a.next() }, List(200) { b.next() })
    }

    @Test
    fun stroopScoringUsesInkNotWord() {
        val t = StroopTrial(word = StroopColor.RED, ink = StroopColor.BLUE)
        assertTrue(scoreStroop(t, StroopColor.BLUE).correct)
        assertFalse(scoreStroop(t, StroopColor.RED).correct) // reading the word is the error the task provokes
        assertTrue(scoreStroop(t, null).omission)
    }

    @Test
    fun cptTargetShareAndNoBackToBackTargets() {
        val g = CptGenerator(5)
        val s = List(6000) { g.next() }
        assertEquals(0.15, s.count { it == 'X' } / 6000.0, 0.03)
        assertTrue(s.zipWithNext().none { (a, b) -> a == 'X' && b == 'X' })
    }

    @Test
    fun cptOutcomes() {
        assertEquals(CptOutcome.HIT, scoreCpt(true, 450))
        assertEquals(CptOutcome.OMISSION, scoreCpt(true, null))
        assertEquals(CptOutcome.COMMISSION, scoreCpt(false, 500))
        assertEquals(CptOutcome.CORRECT_REJECTION, scoreCpt(false, null))
        assertEquals(CptOutcome.ANTICIPATORY, scoreCpt(false, 40))
        assertEquals(CptOutcome.ANTICIPATORY, scoreCpt(true, 99))
    }

    @Test
    fun dPrimeIsZeroAtChanceAndPositiveWhenDiscriminating() {
        assertEquals(0.0, dPrime(10, 20, 50, 100), 0.05)
        assertTrue(dPrime(19, 20, 5, 100) > 2.0)
        assertTrue(dPrime(20, 20, 0, 100).isFinite()) // perfect performance stays finite
    }
}
