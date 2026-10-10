package com.wearlab.watch.session

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** M04 state machine: IDLE > CONFIGURED > STARTING > RUNNING > STOPPING > FINALIZING > COMPLETED; any > ERROR. */
enum class SessionState {
    IDLE, CONFIGURED, STARTING, RUNNING, STOPPING, FINALIZING, COMPLETED, ERROR;

    fun canMoveTo(next: SessionState): Boolean = next == ERROR || when (this) {
        IDLE -> next == CONFIGURED
        CONFIGURED -> next == STARTING || next == IDLE
        STARTING -> next == RUNNING || next == STOPPING
        RUNNING -> next == STOPPING
        STOPPING -> next == FINALIZING
        FINALIZING -> next == COMPLETED
        COMPLETED, ERROR -> next == IDLE
    }
}

/** Config-driven protocol (section 45); loaded from assets/protocols (one JSON per protocol), never hardcoded in logic. */
data class Protocol(
    val id: String,
    val label: String,
    val participantId: String,
    val durationSec: Int,
    val sensors: List<String>,
    val rateHz: Float,
    val metadata: Map<String, String>,
    /** 0 = record continuously for the whole duration. */
    val intervalMin: Int = 0,
    val windowSec: Int = 0,
)

/**
 * What the user can change on the watch. Starts from a protocol's defaults; every field is recorded in
 * the session metadata so a session is reproducible from its files alone.
 */
data class ScheduleConfig(
    val protocolBase: String,
    val totalMin: Int,
    /** Minutes between window starts; 0 = continuous. */
    val intervalMin: Int,
    /** Length of each capture window in seconds (periodic mode). */
    val windowSec: Int,
    val sensors: Set<String>,
    val rateHz: Float,
) {
    val continuous get() = intervalMin <= 0
    val totalSec get() = totalMin * 60
    val effectiveWindowSec get() = if (continuous) totalSec else minOf(windowSec, intervalMin * 60)
    val windowCount get() = if (continuous) 1 else (totalSec + intervalMin * 60 - 1) / (intervalMin * 60)

    fun summary() = if (continuous) "${totalMin} min continuous"
    else "${windowSec}s every ${intervalMin} min, ${totalMin} min total"

    companion object {
        fun from(p: Protocol) = ScheduleConfig(
            p.id, p.durationSec / 60, p.intervalMin, if (p.windowSec > 0) p.windowSec else 60,
            p.sensors.toSet(), p.rateHz,
        )
    }
}

/** Persists the last-used ScheduleConfig so the watch remembers settings between launches. */
object ScheduleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    fun load(c: Context, fallback: ScheduleConfig): ScheduleConfig {
        val p = prefs(c)
        if (!p.contains("base")) return fallback
        return ScheduleConfig(
            p.getString("base", fallback.protocolBase)!!, p.getInt("total", fallback.totalMin),
            p.getInt("interval", fallback.intervalMin), p.getInt("window", fallback.windowSec),
            p.getStringSet("sensors", fallback.sensors)!!.toSet(), p.getFloat("rate", fallback.rateHz),
        )
    }

    fun save(c: Context, s: ScheduleConfig) {
        prefs(c).edit().putString("base", s.protocolBase).putInt("total", s.totalMin)
            .putInt("interval", s.intervalMin).putInt("window", s.windowSec)
            .putStringSet("sensors", s.sensors).putFloat("rate", s.rateHz).apply()
    }
}

object SessionId {
    /** e.g. T001_20261005_142230_BASELINE_001 (UTC). */
    fun make(participant: String, utcMillis: Long, label: String, index: Int): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "${participant}_${f.format(Date(utcMillis))}_${label}_${"%03d".format(index)}"
    }
}
