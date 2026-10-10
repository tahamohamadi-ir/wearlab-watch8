package com.wearlab.watch

import android.content.Context
import android.content.Intent
import com.wearlab.watch.acquisition.StreamSnapshot
import com.wearlab.watch.acquisition.StreamStats
import com.wearlab.watch.core.diagnostics.DeviceInfo
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.android.AndroidSensorAdapter
import com.wearlab.watch.sensor.android.LocationAdapter
import com.wearlab.watch.sensor.android.SystemAdapter
import com.wearlab.watch.sensor.api.*
import com.wearlab.watch.sensor.samsung.SamsungTrackerAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File

data class RunState(
    val running: Boolean = false,
    val snapshot: StreamSnapshot = StreamSnapshot(),
    val lastChannels: List<String> = emptyList(),
    val lastValues: List<Double> = emptyList(),
    /** Last ~120 plot values (one per 0.5 s) for the sparkline. */
    val history: List<Double> = emptyList(),
    val batteryStart: Int? = null,
    val batteryNow: Int? = null,
    val requestedRateHz: Float? = null,
)

/** Process-wide owner of the running streams; InspectorService only keeps the process foreground. */
object InspectorRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<SensorType, Job>()
    private var adapters: List<SensorAdapter> = emptyList()
    var sessions: com.wearlab.watch.session.SessionManager? = null

    private val _state = MutableStateFlow<Map<SensorType, RunState>>(emptyMap())
    val state: StateFlow<Map<SensorType, RunState>> = _state

    fun adapters(context: Context): List<SensorAdapter> {
        if (adapters.isEmpty()) {
            val c = context.applicationContext
            adapters = listOf(
                // motion + environment (platform SensorManager)
                AndroidSensorAdapter.acc(c), AndroidSensorAdapter.gyro(c), AndroidSensorAdapter.rotation(c),
                AndroidSensorAdapter.magnetometer(c), AndroidSensorAdapter.barometer(c), AndroidSensorAdapter.light(c),
                AndroidSensorAdapter.offBody(c), AndroidSensorAdapter.steps(c),
                // physiology (Samsung Health Sensor SDK)
                SamsungTrackerAdapter.ppg(c), SamsungTrackerAdapter.hrIbi(c), SamsungTrackerAdapter.eda(c),
                SamsungTrackerAdapter.skinTemp(c), SamsungTrackerAdapter.skinTempSpot(c),
                // context
                LocationAdapter(c), SystemAdapter(c),
                // manual spot measurements
                SamsungTrackerAdapter.ecg(c), SamsungTrackerAdapter.spo2(c), SamsungTrackerAdapter.mfBia(c),
            )
        }
        return adapters
    }

    private fun update(t: SensorType, f: (RunState) -> RunState) =
        _state.update { it + (t to f(it[t] ?: RunState())) }

    fun start(context: Context, adapter: SensorAdapter, config: SensorConfig = SensorConfig()) {
        val t = adapter.type
        if (jobs[t]?.isActive == true || sessions?.active == true) return
        val app = context.applicationContext
        InspectorService.start(app)
        val b0 = DeviceInfo.batteryPercent(app)
        update(t) { RunState(running = true, batteryStart = b0, batteryNow = b0, requestedRateHz = config.rateHz) }
        jobs[t] = scope.launch {
            val stats = StreamStats()
            var lastPush = 0L
            var error: String? = null
            try {
                adapter.samples(config).collect { s ->
                    stats.record(s.sourceTsNs)
                    val now = Timebase.monotonicNs()
                    if (now - lastPush > 500_000_000L) {
                        lastPush = now
                        update(t) {
                            it.copy(
                                snapshot = stats.snapshot(), lastChannels = s.channels,
                                lastValues = s.values.toList(),
                                history = (it.history + adapter.plotValue(s.values)).takeLast(120), batteryNow = DeviceInfo.batteryPercent(app),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                update(t) { it.copy(running = false, snapshot = stats.snapshot(error ?: it.snapshot.error)) }
                if (jobs.values.none { it.isActive && it !== coroutineContext[Job] }) {
                    InspectorService.stop()
                }
            }
        }
    }

    fun stop(t: SensorType) {
        jobs[t]?.cancel()
    }

    /** Writes capability report plus the last run stats of every sensor; returns the file. */
    suspend fun exportReport(context: Context, permissions: Map<String, Boolean>): File {
        val report = com.wearlab.watch.sensor.capability.CapabilityInspector
            .report(context, adapters(context), permissions)
        val runs = JSONObject()
        for ((t, r) in _state.value) {
            val s = r.snapshot
            runs.put(t.name, JSONObject().apply {
                put("samples", s.samples); put("observed_rate_hz", s.observedRateHz)
                put("mean_dt_ms", s.meanDtMs); put("std_dt_ms", s.stdDtMs); put("max_dt_ms", s.maxDtMs)
                put("gaps", s.gaps); put("dropped_by_seq", s.droppedBySeq); put("duration_ms", s.durationMs)
                put("requested_rate_hz", r.requestedRateHz?.toDouble() ?: JSONObject.NULL)
                put("battery_start", r.batteryStart ?: JSONObject.NULL)
                put("battery_end", r.batteryNow ?: JSONObject.NULL)
                put("error", s.error ?: JSONObject.NULL)
            })
        }
        report.put("last_runs", runs)
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        return File(dir, "capability_report_${System.currentTimeMillis()}.json")
            .also { it.writeText(report.toString(2)) }
    }
}
