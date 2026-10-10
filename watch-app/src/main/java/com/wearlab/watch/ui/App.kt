package com.wearlab.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wearlab.watch.RunState
import com.wearlab.watch.session.ScheduleConfig
import com.wearlab.watch.session.SessionState
import com.wearlab.watch.session.SessionUi
import com.wearlab.watch.sensor.api.Availability
import com.wearlab.watch.sensor.api.SensorAdapter
import com.wearlab.watch.sensor.api.SensorConfig
import com.wearlab.watch.sensor.api.SensorType
import com.wearlab.watch.storage.SessionSummary

private val Cyan = Color(0xFF4DD0E1)
private val Green = Color(0xFF66BB6A)
private val Red = Color(0xFFEF5350)
private val Amber = Color(0xFFFFCA28)
private val Grey = Color(0xFF78909C)
private val Palette = Colors(
    primary = Cyan, primaryVariant = Color(0xFF00838F), secondary = Amber,
    background = Color.Black, surface = Color(0xFF1B2327), error = Red,
    onPrimary = Color.Black, onSecondary = Color.Black, onBackground = Color.White, onSurface = Color.White,
)

class AppState(
    val adapters: List<SensorAdapter>,
    val availability: Map<SensorType, Availability>?,
    val runs: Map<SensorType, RunState>,
    val session: SessionUi,
    val sessions: List<SessionSummary>,
    val schedule: ScheduleConfig,
    val protocols: List<String>,
    val protocolDefaults: (String) -> ScheduleConfig,
    val battery: Int?,
    val exportStatus: String?,
    val syncStatus: String?,
    val syncing: Boolean,
)

class AppActions(
    val onStartSensor: (SensorAdapter, SensorConfig) -> Unit,
    val onStopSensor: (SensorAdapter) -> Unit,
    val onRescan: () -> Unit,
    val onExport: () -> Unit,
    val onStartSession: () -> Unit,
    val onStopSession: () -> Unit,
    val onMark: () -> Unit,
    val onSchedule: (ScheduleConfig) -> Unit,
    val onReloadSessions: () -> Unit,
    val onSyncPhone: () -> Unit,
)

@Composable
fun WearLabApp(state: AppState, a: AppActions, study: com.wearlab.watch.study.StudyRunner, startRoute: String = "home") {
    val nav = rememberSwipeDismissableNavController()
    // NavHost builds its destination lambdas once; without this they would keep the first AppState forever.
    val s by rememberUpdatedState(state)
    MaterialTheme(colors = Palette) {
        SwipeDismissableNavHost(navController = nav, startDestination = startRoute) {
            composable("home") { Home(s, nav::navigate) }
            composable("sensors") { SensorList(s) { nav.navigate("sensor/${it.name}") } }
            composable("sensor/{type}") { e ->
                val t = SensorType.valueOf(e.arguments?.getString("type")!!)
                SensorDetail(s.adapters.first { it.type == t }, s.availability?.get(t), s.runs[t], a)
            }
            composable("logger") { Logger(s, a) }
            composable("settings") { Settings(s, a) }
            composable("sessions") { Sessions(s, a) }
            composable("study") { StudyHost(study) }
        }
    }
}

/** Shared frame: round-screen time at the top, scroll position on the side, soft edge vignette. */
@Composable
private fun Screen(title: String? = null, content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val st: ScalingLazyListState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = st) },
    ) {
        ScalingLazyColumn(
            Modifier.fillMaxSize(), state = st,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) item { Text(title, style = MaterialTheme.typography.title3, color = Cyan) }
            content()
        }
    }
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(10.dp).background(c, CircleShape))

@Composable
private fun Small(text: String, color: Color = Color.Unspecified, bold: Boolean = false) = Text(
    text, fontSize = 11.sp, textAlign = TextAlign.Center, color = color,
    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
)

// ---------------------------------------------------------------- home

@Composable
private fun Home(s: AppState, go: (String) -> Unit) {
    val rec = s.session.state in setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.STOPPING, SessionState.FINALIZING)
    val avail = s.availability?.count { it.value is Availability.Available }
    Screen {
        item {
            Chip(
                onClick = { go("logger") }, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.primaryChipColors(),
                icon = { Dot(if (rec) Red else Color.Black) },
                label = { Text(if (rec) "Recording" else "Record", fontWeight = FontWeight.Bold) },
                secondaryLabel = {
                    Text(
                        if (rec) "${s.session.phase.lowercase()} - ${fmt(s.session.elapsedSec)}" else s.schedule.summary(),
                        maxLines = 1,
                    )
                },
            )
        }
        item { NavChip("Attention study", "Stroop + CPT + EMA, 13 min") { go("study") } }
        item { NavChip("Schedule", s.schedule.summary()) { go("settings") } }
        item { NavChip("Sensors", if (avail == null) "scanning..." else "$avail/${s.availability.size} available") { go("sensors") } }
        item { NavChip("Sessions", "${s.sessions.size} saved") { go("sessions") } }
        item { Small("Battery ${s.battery ?: "?"}%", Grey) }
    }
}

@Composable
private fun NavChip(label: String, secondary: String, onClick: () -> Unit) = Chip(
    onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(),
    label = { Text(label) }, secondaryLabel = { Text(secondary, maxLines = 1) },
)

// ---------------------------------------------------------------- sensors

@Composable
private fun SensorList(s: AppState, open: (SensorType) -> Unit) = Screen("Sensors") {
    for (a in s.adapters) item {
        val av = s.availability?.get(a.type)
        val run = s.runs[a.type]
        val (color, text) = when {
            run?.running == true -> Cyan to "running  %.1f Hz".format(run.snapshot.observedRateHz)
            av == null -> Grey to "checking..."
            av is Availability.Available -> Green to "available"
            av is Availability.Unavailable -> Red to "unavailable"
            else -> Grey to "?"
        }
        Chip(
            onClick = { open(a.type) }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(),
            icon = { Dot(color) }, label = { Text(a.type.label, maxLines = 1) }, secondaryLabel = { Text(text, maxLines = 1) },
        )
    }
}

@Composable
private fun SensorDetail(a: SensorAdapter, av: Availability?, run: RunState?, act: AppActions) {
    val s = run?.snapshot
    Screen(a.type.label) {
        item {
            when {
                av is Availability.Unavailable -> Small(av.reason, Red)
                run?.running == true -> Small("RUNNING", Cyan, bold = true)
                av == null -> Small("checking...", Grey)
                else -> Small("ready", Green)
            }
        }
        if (run != null && run.history.size > 1) item {
            Sparkline(run.history, Modifier.fillMaxWidth(0.85f).height(52.dp), Cyan)
        }
        if (s != null && s.samples > 0) {
            item { Small("%.1f Hz   %d samples".format(s.observedRateHz, s.samples), bold = true) }
            item { Small("dt %.1f / %.1f / %.0f ms".format(s.meanDtMs, s.stdDtMs, s.maxDtMs), Grey) }
            item { Small("gaps ${s.gaps}   ${s.durationMs / 1000}s   batt ${run.batteryStart}->${run.batteryNow}%", Grey) }
            item {
                Small(run.lastChannels.zip(run.lastValues).joinToString("  ") { (c, v) -> "$c=${num(v)}" })
            }
        }
        s?.error?.let { e -> item { Small(e, Red) } }
        item {
            if (run?.running == true) Button(
                onClick = { act.onStopSensor(a) }, colors = ButtonDefaults.buttonColors(backgroundColor = Red),
            ) { Text("STOP") }
            else Button(
                onClick = { act.onStartSensor(a, SensorConfig()) }, enabled = av is Availability.Available,
            ) { Text("START") }
        }
    }
}

private fun num(v: Double) = when {
    v.isNaN() -> "-"
    kotlin.math.abs(v) >= 1000 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
    else -> "%.3f".format(v)
}

@Composable
private fun Sparkline(v: List<Double>, modifier: Modifier, color: Color) = Canvas(modifier) {
    val pts = v.filter { !it.isNaN() }
    if (pts.size < 2) return@Canvas
    val mn = pts.min()
    val range = (pts.max() - mn).takeIf { it > 1e-9 } ?: 1.0
    val path = Path()
    pts.forEachIndexed { i, y ->
        val px = size.width * i / (pts.size - 1)
        val py = size.height * (1f - ((y - mn) / range).toFloat())
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawLine(Color(0x33FFFFFF), Offset(0f, size.height), Offset(size.width, size.height), 1f)
    drawPath(path, color, style = Stroke(width = 3f))
}

// ---------------------------------------------------------------- logger

private fun fmt(sec: Long) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun Logger(s: AppState, a: AppActions) {
    val u = s.session
    val busy = u.state in setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.STOPPING, SessionState.FINALIZING)
    Screen {
        item {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = if (u.totalSec > 0) (u.elapsedSec.toFloat() / u.totalSec).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.fillMaxSize(), strokeWidth = 6.dp,
                    indicatorColor = if (u.phase == "WAITING") Amber else Cyan,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(u.state.name, fontSize = 11.sp, color = if (u.state == SessionState.ERROR) Red else Grey)
                    Text(fmt(u.elapsedSec), style = MaterialTheme.typography.display2)
                    Text(
                        when {
                            !busy -> s.schedule.summary()
                            u.phase == "WAITING" -> "next in ${fmt(u.nextWindowInSec)}"
                            else -> "capturing ${fmt(u.windowRemainingSec)}"
                        },
                        fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    if (busy && u.windowCount > 1) Text("window ${u.windowIndex + 1}/${u.windowCount}", fontSize = 10.sp, color = Grey)
                }
            }
        }
        item {
            if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = a.onMark, enabled = u.state == SessionState.RUNNING) { Text("MARK", fontSize = 12.sp) }
                Button(
                    onClick = a.onStopSession, enabled = u.state == SessionState.RUNNING,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Red),
                ) { Text("STOP", fontSize = 12.sp) }
            } else Button(onClick = a.onStartSession) { Text("START") }
        }
        u.message?.let { m -> item { Small(m, if (u.state == SessionState.ERROR) Red else Grey) } }
        for ((t, st) in u.streams) item {
            val bad = st.error != null || st.overflowDropped > 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(if (bad) Red else if (st.snapshot.samples > 0) Green else Grey)
                Small("${t.name.take(9)} ${st.snapshot.samples}  %.1fHz".format(st.snapshot.observedRateHz))
            }
        }
    }
}

// ---------------------------------------------------------------- settings

private val TOTALS = listOf(1, 2, 5, 10, 15, 30, 60, 120, 240, 480, 720)
private val INTERVALS = listOf(0, 1, 2, 5, 10, 15, 20, 30, 60)
private val WINDOWS = listOf(10, 20, 30, 45, 60, 90, 120, 180, 300)
private val RATES = listOf(10f, 25f, 50f, 100f)
private val RECORDABLE = SensorType.values().filter { !it.manual }

@Composable
private fun Settings(s: AppState, a: AppActions) {
    val c = s.schedule
    fun set(n: ScheduleConfig) = a.onSchedule(
        // a window can never be longer than the interval it repeats in
        if (n.intervalMin > 0 && n.windowSec > n.intervalMin * 60) n.copy(windowSec = n.intervalMin * 60) else n,
    )
    Screen("Schedule") {
        item {
            val next = s.protocols[(s.protocols.indexOf(c.protocolBase) + 1).mod(s.protocols.size)]
            NavChip("Preset", c.protocolBase) { a.onSchedule(s.protocolDefaults(next)) }
        }
        item { Picker("Total time", TOTALS, c.totalMin, { if (it >= 60) "${it / 60} h" else "$it min" }) { set(c.copy(totalMin = it)) } }
        item { Picker("Capture every", INTERVALS, c.intervalMin, { if (it == 0) "continuous" else "$it min" }) { set(c.copy(intervalMin = it)) } }
        if (c.intervalMin > 0) item {
            Picker("Window length", WINDOWS.filter { it <= c.intervalMin * 60 }.ifEmpty { listOf(c.intervalMin * 60) }, c.windowSec, { "$it s" }) {
                set(c.copy(windowSec = it))
            }
        }
        item { Picker("Sample rate", RATES, c.rateHz, { "%.0f Hz".format(it) }) { set(c.copy(rateHz = it)) } }
        item {
            ToggleChip(
                checked = c.keepScreenOn, modifier = Modifier.fillMaxWidth(),
                onCheckedChange = { set(c.copy(keepScreenOn = it)) },
                label = { Text("Keep screen on") },
                secondaryLabel = { Text("steadier HR/EDA/PPG, more battery", maxLines = 2) },
                toggleControl = { Icon(imageVector = ToggleChipDefaults.switchIcon(c.keepScreenOn), contentDescription = null) },
            )
        }
        item { Small(estimate(c), Grey) }
        item { Text("Sensors", style = MaterialTheme.typography.caption1, color = Cyan) }
        for (t in RECORDABLE) item {
            val on = t.name in c.sensors
            ToggleChip(
                checked = on, modifier = Modifier.fillMaxWidth(),
                onCheckedChange = { set(c.copy(sensors = if (it) c.sensors + t.name else c.sensors - t.name)) },
                label = { Text(t.label, maxLines = 1) },
                toggleControl = { Icon(imageVector = ToggleChipDefaults.switchIcon(on), contentDescription = null) },
            )
        }
    }
}

private fun estimate(c: ScheduleConfig) =
    if (c.continuous) "${c.sensors.size} sensors for ${c.totalMin} min"
    else "${c.windowCount} windows x ${c.effectiveWindowSec}s = ${c.windowCount * c.effectiveWindowSec / 60} min of data"

@Composable
private fun <T> Picker(label: String, options: List<T>, current: T, show: (T) -> String, onPick: (T) -> Unit) {
    val i = options.indexOf(current).let { if (it < 0) options.indexOfFirst { o -> o.toString().toDoubleOrNull()?.let { x -> x >= (current.toString().toDoubleOrNull() ?: 0.0) } == true }.coerceAtLeast(0) else it }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Small(label, Grey)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactChip(onClick = { onPick(options[(i - 1).coerceAtLeast(0)]) }, label = { Text("-") })
            Text(show(options[i]), modifier = Modifier.padding(horizontal = 2.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            CompactChip(onClick = { onPick(options[(i + 1).coerceAtMost(options.lastIndex)]) }, label = { Text("+") })
        }
    }
}

// ---------------------------------------------------------------- sessions

@Composable
private fun Sessions(s: AppState, a: AppActions) = Screen("Sessions") {
    if (s.sessions.isEmpty()) item { Small("nothing recorded yet", Grey) }
    for (x in s.sessions) item {
        val color = when (x.status) { "COMPLETED" -> Green; "INTERRUPTED", "ERROR" -> Red; else -> Amber }
        Chip(
            onClick = {}, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors(),
            icon = { Dot(color) },
            label = { Text(x.id.substringAfter('_').take(13) + " " + x.id.substringAfterLast('_'), fontSize = 12.sp, maxLines = 1) },
            secondaryLabel = { Text("${x.status.lowercase()}  ${x.bytes / 1024} KB  ${x.transfer.lowercase()}", fontSize = 10.sp, maxLines = 1) },
        )
    }
    item {
        Chip(
            onClick = a.onSyncPhone, enabled = !s.syncing, modifier = Modifier.fillMaxWidth(),
            colors = ChipDefaults.primaryChipColors(),
            label = { Text(if (s.syncing) "Sending..." else "Send to phone") },
            secondaryLabel = { Text(s.syncStatus ?: "checksum-verified", maxLines = 2) },
        )
    }
    item { CompactChip(onClick = a.onReloadSessions, label = { Text("Reload") }) }
    item { CompactChip(onClick = a.onExport, label = { Text("Export report") }) }
    s.exportStatus?.let { e -> item { Small(e, Grey) } }
}
