package com.wearlab.watch.study

import android.content.Context
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.SensorAdapter
import com.wearlab.watch.session.ScheduleConfig
import com.wearlab.watch.session.SessionManager
import com.wearlab.watch.session.SessionState
import com.wearlab.watch.storage.SessionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.Random

data class Phase(val id: String, val type: String, val sec: Int)
data class Study(val id: String, val sessionProtocol: String, val emaItems: List<String>, val phases: List<Phase>)

fun loadStudy(c: Context, name: String): Study {
    val j = JSONObject(c.assets.open("studies/$name.json").bufferedReader().use { it.readText() })
    val ph = j.getJSONArray("phases")
    val ema = j.getJSONArray("ema_items")
    return Study(
        j.getString("study_id"), j.getString("session_protocol"),
        List(ema.length()) { ema.getString(it) },
        List(ph.length()) { ph.getJSONObject(it).let { p -> Phase(p.getString("id"), p.getString("type"), p.getInt("sec")) } },
    )
}

/** What the watch shows. Each variant is one screen of the study. */
sealed interface StudyScreen {
    data object Idle : StudyScreen
    data class Starting(val msg: String) : StudyScreen
    data class Ema(val stage: String, val item: String, val index: Int, val total: Int) : StudyScreen
    data class Rest(val phase: String, val remainingSec: Int) : StudyScreen
    /** trial == null is the blank inter-trial interval. [id] changes for every stimulus so the UI can stamp its onset. */
    data class Stroop(val id: Int, val trial: StroopTrial?, val remainingSec: Int) : StudyScreen
    data class Cpt(val id: Int, val letter: Char?, val remainingSec: Int) : StudyScreen
    data class Done(val summary: String) : StudyScreen
    data class Error(val msg: String) : StudyScreen
}

/**
 * Runs a study (rest / Stroop / CPT phases with EMA before and after) *inside* a normal recording session, so every
 * stimulus, answer and phase boundary is stamped on the same monotonic clock as the sensors (no cross-device sync).
 * Stimulus onset is stamped when the UI reports its first frame (+- one frame, ~16 ms).
 */
class StudyRunner(private val ctx: Context, private val store: SessionStore, private val mgr: SessionManager,
                  private val adapters: () -> List<SensorAdapter>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _ui = MutableStateFlow<StudyScreen>(StudyScreen.Idle)
    val ui: StateFlow<StudyScreen> = _ui

    private class Tap(val mono: Long, val value: Int = 0)
    private val taps = Channel<Tap>(Channel.UNLIMITED)
    private val stroopAnswers = Channel<Pair<StroopColor, Long>>(Channel.UNLIMITED)
    private val emaAnswers = Channel<Tap>(Channel.UNLIMITED)
    @Volatile private var shown = CompletableDeferred<Pair<Int, Long>>()

    val running get() = job?.isActive == true

    // ---- input from the UI
    fun stroopAnswer(c: StroopColor) { stroopAnswers.trySend(c to Timebase.monotonicNs()) }
    fun cptTap() { taps.trySend(Tap(Timebase.monotonicNs())) }
    fun emaSubmit(v: Int) { emaAnswers.trySend(Tap(Timebase.monotonicNs(), v)) }
    /** The UI calls this after the frame that contains stimulus [id] was drawn. */
    fun stimulusShown(id: Int) { shown.complete(id to Timebase.monotonicNs()) }

    fun cancel() {
        job?.cancel()
        mgr.requestStop()
        _ui.value = StudyScreen.Idle
    }

    fun dismiss() { if (!running) _ui.value = StudyScreen.Idle }

    fun start(studyName: String) {
        if (running || mgr.active) return
        job = scope.launch {
            try {
                run(studyName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.value = StudyScreen.Error(e.message ?: e.toString())
                mgr.requestStop()
            }
        }
    }

    /** First tap at or after [onset] and before [deadline] (both monotonic ns); earlier taps belong to the previous stimulus. */
    private suspend fun nextTap(onset: Long, deadline: Long): Tap? {
        while (true) {
            val left = (deadline - Timebase.monotonicNs()) / 1_000_000L
            if (left <= 0) return null
            val t = withTimeoutOrNull(left) { taps.receive() } ?: return null
            if (t.mono >= onset) return t
        }
    }

    private suspend fun nextAnswer(onset: Long, timeoutMs: Long): Pair<StroopColor, Long>? {
        val deadline = onset + timeoutMs * 1_000_000L
        while (true) {
            val left = (deadline - Timebase.monotonicNs()) / 1_000_000L
            if (left <= 0) return null
            val a = withTimeoutOrNull(left) { stroopAnswers.receive() } ?: return null
            if (a.second >= onset) return a
        }
    }

    private suspend fun awaitShown(id: Int): Long {
        val r = withTimeoutOrNull(600) { shown.await() }
        shown = CompletableDeferred()
        return if (r != null && r.first == id) r.second else Timebase.monotonicNs()
    }

    private suspend fun run(name: String) {
        _ui.value = StudyScreen.Starting("starting sensors...")
        val study = loadStudy(ctx, name)
        val cfg = ScheduleConfig.from(store.loadProtocol(study.sessionProtocol))
        mgr.start(cfg, adapters())
        withTimeoutOrNull(30_000) { while (!mgr.recording) delay(200) } ?: error("recording did not start: ${mgr.ui.value.message ?: mgr.ui.value.state}")
        val seed = System.nanoTime()
        val stroopGen = StroopGenerator(seed)
        val cptGen = CptGenerator(seed xor 0x5DEECE66DL)
        mgr.logEvent("study_start", "study=${study.id} seed=$seed")

        ema(study, "PRE")
        var stroopN = 0; var stroopOk = 0; var stroopOm = 0
        val cpt = IntArray(5)
        var cptT = 0; var cptNT = 0
        for (p in study.phases) {
            mgr.logEvent("phase_start", "${p.id} ${p.type} sec=${p.sec}")
            val end = Timebase.monotonicNs() + p.sec * 1_000_000_000L
            fun left() = ((end - Timebase.monotonicNs()) / 1_000_000_000L).toInt().coerceAtLeast(0)
            when (p.type) {
                "REST" -> while (Timebase.monotonicNs() < end) { _ui.value = StudyScreen.Rest(p.id, left()); delay(500) }
                "STROOP" -> {
                    var n = 0
                    val rnd = Random(seed)
                    while (Timebase.monotonicNs() < end) {
                        val trial = stroopGen.next()
                        _ui.value = StudyScreen.Stroop(n * 2, null, left())
                        delay(500L + rnd.nextInt(300))
                        while (stroopAnswers.tryReceive().isSuccess) { /* discard taps from the blank */ }
                        val id = n * 2 + 1
                        _ui.value = StudyScreen.Stroop(id, trial, left())
                        val onset = awaitShown(id)
                        val ans = nextAnswer(onset, 3000)
                        val sc = scoreStroop(trial, ans?.first)
                        n++; stroopN++
                        if (sc.correct) stroopOk++
                        if (sc.omission) stroopOm++
                        mgr.logTask(onset, "STROOP", p.id, n, "${trial.word}/${trial.ink}/${if (trial.congruent) "C" else "I"}", trial.ink.name,
                            ans?.first?.name ?: "", if (sc.omission) "OMISSION" else if (sc.correct) "CORRECT" else "ERROR",
                            ans?.let { (it.second - onset) / 1_000_000L })
                    }
                }
                "CPT" -> {
                    var n = 0
                    while (Timebase.monotonicNs() < end) {
                        val letter = cptGen.next()
                        while (taps.tryReceive().isSuccess) { /* discard */ }
                        n++
                        val id = n
                        _ui.value = StudyScreen.Cpt(id, letter, left())
                        val onset = awaitShown(id)
                        val soa = onset + 1_250_000_000L
                        var tap: Tap? = null
                        coroutineScope {
                            launch { delay(250); _ui.value = StudyScreen.Cpt(id, null, left()) } // stimulus visible for 250 ms only
                            tap = nextTap(onset, soa)
                        }
                        val rem = (soa - Timebase.monotonicNs()) / 1_000_000L
                        if (rem > 0) delay(rem)
                        val rt = tap?.let { (it.mono - onset) / 1_000_000L }
                        val isT = letter == cptGen.target
                        val out = scoreCpt(isT, rt)
                        cpt[out.ordinal]++
                        if (isT) cptT++ else cptNT++
                        mgr.logTask(onset, "CPT", p.id, n, letter.toString(), if (isT) "TAP" else "NO_TAP", if (tap != null) "TAP" else "",
                            out.name, rt)
                    }
                }
            }
            mgr.logEvent("phase_stop", p.id)
        }
        ema(study, "POST")
        mgr.logEvent("study_end", study.id)
        mgr.requestStop()
        withTimeoutOrNull(60_000) { while (mgr.ui.value.state != SessionState.COMPLETED && mgr.ui.value.state != SessionState.ERROR) delay(300) }
        val hit = cpt[CptOutcome.HIT.ordinal]; val om = cpt[CptOutcome.OMISSION.ordinal]; val co = cpt[CptOutcome.COMMISSION.ordinal]
        _ui.value = StudyScreen.Done(
            "Stroop $stroopOk/$stroopN correct, $stroopOm missed\nCPT hits $hit/$cptT, omissions $om, commissions $co\n" +
                (mgr.ui.value.message ?: ""),
        )
    }

    private suspend fun ema(study: Study, stage: String) {
        mgr.logEvent("ema_start", stage)
        for ((i, item) in study.emaItems.withIndex()) {
            _ui.value = StudyScreen.Ema(stage, item, i, study.emaItems.size)
            while (emaAnswers.tryReceive().isSuccess) { /* stale */ }
            val a = emaAnswers.receive()
            mgr.logTask(a.mono, "EMA_$stage", "EMA", i + 1, item, "", a.value.toString(), "ANSWERED", null)
        }
        mgr.logEvent("ema_end", stage)
    }
}
