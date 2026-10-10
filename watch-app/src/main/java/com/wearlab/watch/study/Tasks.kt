package com.wearlab.watch.study

import java.util.Random

/**
 * Pure task logic (no Android, no clock): trial generation and scoring, unit-tested on the JVM.
 * Timing is measured by the runner on the same monotonic clock the sensors use.
 */
enum class StroopColor(val argb: Long) {
    RED(0xFFE53935), GREEN(0xFF43A047), BLUE(0xFF1E88E5), YELLOW(0xFFFDD835),
}

data class StroopTrial(val word: StroopColor, val ink: StroopColor) {
    val congruent get() = word == ink
}

/** Seeded so a session can be reproduced from its metadata. Half congruent / half incongruent by default. */
class StroopGenerator(seed: Long, private val congruentShare: Double = 0.5) {
    private val rnd = Random(seed)
    private val colors = StroopColor.values()

    fun next(): StroopTrial {
        val word = colors[rnd.nextInt(colors.size)]
        val ink = if (rnd.nextDouble() < congruentShare) word else colors.filter { it != word }[rnd.nextInt(colors.size - 1)]
        return StroopTrial(word, ink)
    }
}

/** Correct = the button matching the INK colour (never the word). A missing answer is an omission. */
data class StroopScore(val correct: Boolean, val omission: Boolean)

fun scoreStroop(trial: StroopTrial, answer: StroopColor?): StroopScore =
    if (answer == null) StroopScore(false, true) else StroopScore(answer == trial.ink, false)

/**
 * Continuous Performance Test: letters appear one at a time; respond only to the target ('X').
 * Roughly 15 % targets, never two targets back to back.
 */
class CptGenerator(seed: Long, private val targetShare: Double = 0.15, val target: Char = 'X') {
    private val rnd = Random(seed)
    private val pool = ('A'..'Z').filter { it != target }
    private var lastWasTarget = false

    fun next(): Char {
        val t = !lastWasTarget && rnd.nextDouble() < targetShare
        lastWasTarget = t
        return if (t) target else pool[rnd.nextInt(pool.size)]
    }
}

enum class CptOutcome { HIT, OMISSION, COMMISSION, CORRECT_REJECTION, ANTICIPATORY }

const val CPT_MIN_RT_MS = 100L

/** rtMs = first tap after onset (null = no tap). A tap faster than 100 ms cannot be a response to the stimulus. */
fun scoreCpt(isTarget: Boolean, rtMs: Long?): CptOutcome = when {
    rtMs != null && rtMs < CPT_MIN_RT_MS -> CptOutcome.ANTICIPATORY
    isTarget && rtMs != null -> CptOutcome.HIT
    isTarget -> CptOutcome.OMISSION
    rtMs != null -> CptOutcome.COMMISSION
    else -> CptOutcome.CORRECT_REJECTION
}

/** Signal-detection sensitivity d' with the standard 1/(2N) correction so hit/false-alarm rates of 0 or 1 stay finite. */
fun dPrime(hits: Int, targets: Int, falseAlarms: Int, nonTargets: Int): Double {
    fun z(p: Double): Double {
        // Acklam-style rational approximation of the inverse normal CDF, accurate to ~1e-4 which is ample here.
        val a = doubleArrayOf(-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239)
        val b = doubleArrayOf(-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572)
        val c = doubleArrayOf(-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
        val d = doubleArrayOf(0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416)
        val lo = 0.02425
        return when {
            p < lo -> { val q = Math.sqrt(-2 * Math.log(p)); (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            p <= 1 - lo -> { val q = p - 0.5; val r = q * q; (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1) }
            else -> { val q = Math.sqrt(-2 * Math.log(1 - p)); -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
        }
    }
    val h = ((hits + 0.5) / (targets + 1.0)).coerceIn(1.0 / (2 * targets.coerceAtLeast(1)), 1 - 1.0 / (2 * targets.coerceAtLeast(1)))
    val f = ((falseAlarms + 0.5) / (nonTargets + 1.0)).coerceIn(1.0 / (2 * nonTargets.coerceAtLeast(1)), 1 - 1.0 / (2 * nonTargets.coerceAtLeast(1)))
    return z(h) - z(f)
}
