package com.wearlab.watch.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.wearlab.watch.InspectorService
import com.wearlab.watch.acquisition.StreamSnapshot
import com.wearlab.watch.acquisition.StreamStats
import com.wearlab.watch.core.diagnostics.DeviceInfo
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.*
import com.wearlab.watch.storage.CsvStreamWriter
import com.wearlab.watch.storage.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

data class StreamUi(val snapshot: StreamSnapshot = StreamSnapshot(), val overflowDropped: Long = 0, val error: String? = null)

data class SessionUi(
    val state: SessionState = SessionState.IDLE,
    val sessionId: String? = null,
    val elapsedSec: Long = 0,
    val totalSec: Int = 0,
    /** "WINDOW" while sensors are capturing, "WAITING" between windows. */
    val phase: String = "",
    val windowIndex: Int = 0,
    val windowCount: Int = 0,
    val windowRemainingSec: Long = 0,
    val nextWindowInSec: Long = 0,
    val streams: Map<SensorType, StreamUi> = emptyMap(),
    val message: String? = null,
)

/**
 * M04/M05/M07/M08/M09: runs a ScheduleConfig, buffers samples, writes raw CSV streams plus manifest.
 * Continuous mode captures for the whole duration; periodic mode captures `windowSec` every
 * `intervalMin` minutes and lets the CPU sleep in between (alarm wakes it for the next window).
 */
class SessionManager(private val context: Context, private val store: SessionStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui

    @Volatile private var stopRequested = false
    private val markQueue = ConcurrentLinkedQueue<Pair<Long, Long>>()
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    val active: Boolean get() = _ui.value.state in setOf(
        SessionState.STARTING, SessionState.RUNNING, SessionState.STOPPING, SessionState.FINALIZING,
    )

    private fun move(next: SessionState, msg: String? = null) {
        val cur = _ui.value.state
        check(cur.canMoveTo(next)) { "illegal transition $cur > $next" }
        _ui.value = _ui.value.copy(state = next, message = msg)
    }

    fun requestStop() {
        stopRequested = true
        wakeSignal.trySend(Unit)
    }

    /** Timestamps an external event (EMA answer, task onset...) on both clocks; written as a `marker` event. */
    fun mark() {
        if (_ui.value.state == SessionState.RUNNING) markQueue.add(Timebase.monotonicNs() to Timebase.utcNs())
    }

    fun start(cfg: ScheduleConfig, adapters: List<SensorAdapter>) {
        if (active) return
        stopRequested = false
        wakeSignal.tryReceive()
        _ui.value = SessionUi()
        scope.launch { runSession(cfg, adapters) }
    }

    private class Item(val window: Int, val sample: SensorSample)

    private class Stream(val type: SensorType, val adapter: SensorAdapter) {
        lateinit var writer: CsvStreamWriter
        val stats = StreamStats()
        val dropped = AtomicLong()
        val enqueued = AtomicLong()
        val queue = Channel<Item>(8192)
        @Volatile var error: String? = null
    }

    private suspend fun runSession(cfg: ScheduleConfig, adapters: List<SensorAdapter>) {
        val app = context.applicationContext
        val errors = JSONArray()
        var id = ""
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                InspectorService.awake(true) // CPU must stay up from the moment the alarm fires
                wakeSignal.trySend(Unit)
            }
        }
        try {
            val p = store.loadProtocol(cfg.protocolBase)
            val base = ScheduleConfig.from(p)
            val protocolId = if (cfg == base) p.id else p.id + "+CUSTOM"
            move(SessionState.CONFIGURED)
            id = SessionId.make(p.participantId, System.currentTimeMillis(), p.label, store.nextIndex())
            val dir = store.dir(id)
            _ui.value = _ui.value.copy(sessionId = id, totalSec = cfg.totalSec, windowCount = cfg.windowCount)
            move(SessionState.STARTING)
            InspectorService.start(app)
            app.registerReceiver(receiver, IntentFilter(InspectorService.ACTION_WINDOW), Context.RECEIVER_NOT_EXPORTED)

            val streams = adapters.filter { it.type.name in cfg.sensors }
                .filter { it.availability() is Availability.Available }
                .map { Stream(it.type, it) }
            if (streams.isEmpty()) throw IllegalStateException("no available sensors selected")
            cfg.sensors.filter { n -> streams.none { it.type.name == n } }
                .forEach { errors.put("sensor $it unavailable at start; not recorded") }

            val startMono = Timebase.monotonicNs()
            val startUtc = Timebase.utcNs()
            val batt0 = DeviceInfo.batteryPercent(app)
            val metadata = JSONObject().apply {
                put("participant_id", p.participantId); put("session_id", id); put("protocol_id", protocolId)
                put("device", DeviceInfo.toJson(app)); put("research_app_version", "0.4.0")
                put("start_utc_ns", startUtc.toString()); put("start_monotonic_ns", startMono.toString())
                put("battery_start", batt0 ?: JSONObject.NULL)
                put("sampling_configuration", JSONObject().apply {
                    put("requested_rate_hz", cfg.rateHz.toDouble())
                    put("mode", if (cfg.continuous) "continuous" else "periodic")
                    put("total_min", cfg.totalMin); put("interval_min", cfg.intervalMin)
                    put("window_sec", cfg.effectiveWindowSec); put("planned_windows", cfg.windowCount)
                    put("sensors", JSONArray(cfg.sensors.sorted()))
                })
                put("protocol_metadata", JSONObject(p.metadata as Map<*, *>))
                put("sensor_hardware", JSONObject().apply {
                    for (st in streams) put(st.type.name, JSONObject(st.adapter.hardwareInfo()).put("channels", JSONArray(st.adapter.channels)))
                })
                put("phone_gateway", "none (Phase 4)")
            }
            store.writeJson(File(dir, "metadata.json"), metadata)
            var windowsDone = 0
            fun event(name: String, detail: String = "", mono: Long = Timebase.monotonicNs(), utc: Long = Timebase.utcNs()) =
                store.appendEvent(id, JSONObject().apply {
                    put("utc_ns", utc); put("monotonic_ns", mono)
                    put("session_offset_ns", mono - startMono)
                    put("event", name); put("detail", detail)
                })

            fun manifest(status: String, endUtc: Long?, batt1: Int?, final: Boolean) = JSONObject().apply {
                put("session_id", id); put("status", status); put("protocol_id", protocolId)
                put("start_utc", startUtc.toString()); put("end_utc", endUtc?.toString() ?: JSONObject.NULL)
                put("battery_start", batt0 ?: JSONObject.NULL); put("battery_end", batt1 ?: JSONObject.NULL)
                put("windows_completed", windowsDone)
                put("streams", JSONObject().apply {
                    for (s in streams) put(s.type.name, JSONObject().apply {
                        val snap = s.stats.snapshot()
                        val file = File(dir, s.type.name.lowercase() + ".csv")
                        put("file", file.name); put("samples", s.writer.samples); put("bytes", file.length())
                        put("first_utc_ns", s.writer.firstUtcNs.toString()); put("last_utc_ns", s.writer.lastUtcNs.toString())
                        put("sha256", if (final) s.writer.sha256 ?: JSONObject.NULL else JSONObject.NULL)
                        put("observed_rate_hz", snap.observedRateHz); put("gaps", snap.gaps)
                        put("max_dt_ms", snap.maxDtMs); put("overflow_dropped", s.dropped.get())
                        put("error", s.error ?: JSONObject.NULL)
                    })
                })
                put("errors", errors); put("export_status", "PENDING")
            }

            streams.forEach { it.writer = CsvStreamWriter(File(dir, it.type.name.lowercase() + ".csv"), it.adapter.channels) }
            store.writeJson(File(dir, "manifest.json"), manifest("RUNNING", null, null, false))

            fun uiStreams() = streams.associate { it.type to StreamUi(it.stats.snapshot(), it.dropped.get(), it.error) }
            fun elapsedSec() = (Timebase.monotonicNs() - startMono) / 1_000_000_000L
            fun drainMarks() {
                while (true) { val mk = markQueue.poll() ?: break; event("marker", "", mk.first, mk.second) }
            }

            coroutineScope {
                val writers = streams.map { s ->
                    launch(Dispatchers.IO) {
                        var lastFlush = System.nanoTime()
                        var lastWindow = -1
                        for (it in s.queue) {
                            if (it.window != lastWindow) { s.stats.boundary(); lastWindow = it.window }
                            val x = it.sample
                            s.writer.write(it.window, x.sequence, x.sourceTsNs, x.monoNs, x.utcNs, x.monoNs - startMono, x.quality, x.values)
                            s.stats.record(x.sourceTsNs)
                            if (System.nanoTime() - lastFlush > 1_000_000_000L) { s.writer.flush(); lastFlush = System.nanoTime() }
                        }
                        s.writer.flush()
                    }
                }
                move(SessionState.RUNNING); event("session_running", id)

                val totalNs = cfg.totalSec * 1_000_000_000L
                val intervalNs = cfg.intervalMin * 60_000_000_000L
                val windowNs = cfg.effectiveWindowSec * 1_000_000_000L
                var k = 0
                var tick = 0

                while (!stopRequested) {
                    val scheduled = if (cfg.continuous) 0L else k * intervalNs
                    if (scheduled >= totalNs || (k > 0 && cfg.continuous)) break

                    // ---- waiting phase (periodic only): CPU may sleep, alarm wakes it ----
                    if (!cfg.continuous && Timebase.monotonicNs() - startMono < scheduled) {
                        wakeSignal.tryReceive()
                        val targetMono = startMono + scheduled
                        InspectorService.scheduleWindow(app, SystemClock.elapsedRealtime() + (targetMono - Timebase.monotonicNs()) / 1_000_000L)
                        event("waiting", "next window ${k} at offset ${scheduled / 1_000_000_000L}s")
                        InspectorService.awake(false)
                        manifest("RUNNING", null, DeviceInfo.batteryPercent(app), false).let { store.writeJson(File(dir, "manifest.json"), it) }
                        while (!stopRequested && Timebase.monotonicNs() < targetMono) {
                            _ui.value = _ui.value.copy(
                                phase = "WAITING", elapsedSec = elapsedSec(), windowIndex = k,
                                nextWindowInSec = maxOf(0, (targetMono - Timebase.monotonicNs()) / 1_000_000_000L),
                                streams = uiStreams(),
                            )
                            drainMarks()
                            if (withTimeoutOrNull(1000) { wakeSignal.receive() } != null) break
                        }
                        InspectorService.cancelWindow(app)
                        InspectorService.awake(true)
                        if (stopRequested) break
                    }

                    // ---- capture window ----
                    val wStart = Timebase.monotonicNs()
                    val wEnd = minOf(if (cfg.continuous) startMono + totalNs else wStart + windowNs, startMono + totalNs)
                    event("window_start", "index=$k scheduled_offset_s=${scheduled / 1_000_000_000L} late_ms=${(wStart - startMono - scheduled) / 1_000_000L}")
                    val before = streams.associate { it.type to it.enqueued.get() }
                    val collectors = streams.map { s ->
                        launch {
                            event("sensor_start", s.type.name)
                            try {
                                s.adapter.samples(SensorConfig(cfg.rateHz)).collect { x ->
                                    if (s.queue.trySend(Item(k, x)).isSuccess) s.enqueued.incrementAndGet()
                                    else if (s.dropped.getAndIncrement() == 0L) event("buffer_overflow", s.type.name)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                s.error = e.message ?: e.toString()
                                errors.put("${s.type} (window $k): ${s.error}"); event("sensor_error", "${s.type}: ${s.error}")
                            } finally { event("sensor_stop", s.type.name) }
                        }
                    }
                    event("clock_sync", "utc_minus_mono_ns=${Timebase.utcNs() - Timebase.monotonicNs()}")
                    while (!stopRequested && Timebase.monotonicNs() < wEnd) {
                        delay(500)
                        if (collectors.all { !it.isActive }) { errors.put("all sensors stopped in window $k"); break }
                        _ui.value = _ui.value.copy(
                            phase = "WINDOW", elapsedSec = elapsedSec(), windowIndex = k,
                            windowRemainingSec = maxOf(0, (wEnd - Timebase.monotonicNs()) / 1_000_000_000L),
                            streams = uiStreams(),
                        )
                        // Clock sync points: UTC-minus-monotonic drifts if the wall clock is adjusted mid-session.
                        if (tick % 20 == 0) { val m = Timebase.monotonicNs(); val u = Timebase.utcNs(); event("clock_sync", "utc_minus_mono_ns=${u - m}", m, u) }
                        drainMarks()
                        if (++tick % 10 == 0) store.writeJson(File(dir, "manifest.json"), manifest("RUNNING", null, null, false))
                    }
                    collectors.forEach { it.cancelAndJoin() }
                    // Completeness record: a stream with zero samples in a window (e.g. GPS indoors) must be visible.
                    val counts = streams.joinToString(";") { st -> "${st.type.name}=${st.enqueued.get() - (before[st.type] ?: 0)}" }
                    streams.filter { st -> st.enqueued.get() == (before[st.type] ?: 0) }.forEach { st ->
                        errors.put("${st.type}: no samples in window $k${st.error?.let { e -> " ($e)" } ?: ""}")
                    }
                    event("window_stop", "index=$k $counts")
                    windowsDone++
                    k++
                    if (cfg.continuous) break
                }

                move(SessionState.STOPPING)
                streams.forEach { it.queue.close() }
                writers.joinAll()
                move(SessionState.FINALIZING)
                streams.forEach { it.writer.close() }
                val endUtc = Timebase.utcNs()
                val failed = streams.all { it.error != null } && streams.none { it.writer.samples > 0 }
                store.writeJson(File(dir, "manifest.json"), manifest(if (failed) "ERROR" else "COMPLETED", endUtc, DeviceInfo.batteryPercent(app), true))
                event("session_finalized", id)
                _ui.value = _ui.value.copy(phase = "", streams = uiStreams())
                if (failed) move(SessionState.ERROR, "all sensors failed") else move(SessionState.COMPLETED, "saved $id")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _ui.value = _ui.value.copy(state = SessionState.ERROR, phase = "", message = e.message ?: e.toString())
            if (id.isNotEmpty()) runCatching {
                store.appendEvent(id, JSONObject().put("utc_ns", Timebase.utcNs()).put("event", "session_error").put("detail", e.toString()))
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { InspectorService.cancelWindow(app) }
            InspectorService.stop()
        }
    }
}
