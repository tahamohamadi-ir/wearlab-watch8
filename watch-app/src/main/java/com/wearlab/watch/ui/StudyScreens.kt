package com.wearlab.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import com.wearlab.watch.study.StudyRunner
import com.wearlab.watch.study.StudyScreen
import com.wearlab.watch.study.StroopColor

private val Cyan = Color(0xFF4DD0E1)
private val Grey = Color(0xFF78909C)

/** Full-screen host for the attention study. The recording screen stays on for the whole study (keep_screen_on in the protocol). */
@Composable
fun StudyHost(runner: StudyRunner) {
    val s by runner.ui.collectAsState()
    // Leaving the screen mid-study ends it cleanly (the session is finalized, data so far is kept).
    DisposableEffect(Unit) { onDispose { if (runner.running) runner.cancel() } }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when (val st = s) {
            StudyScreen.Idle -> StudyStart(runner)
            is StudyScreen.Starting -> Msg(st.msg)
            is StudyScreen.Ema -> EmaScreen(st, runner)
            is StudyScreen.Rest -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Rest", color = Grey, fontSize = 13.sp)
                Text("%d:%02d".format(st.remainingSec / 60, st.remainingSec % 60), style = MaterialTheme.typography.display1)
                Text("sit still, breathe normally,\nkeep the arm relaxed", fontSize = 11.sp, textAlign = TextAlign.Center, color = Grey)
            }
            is StudyScreen.Stroop -> StroopScreen(st, runner)
            is StudyScreen.Cpt -> CptScreen(st, runner)
            is StudyScreen.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("Done", color = Cyan, style = MaterialTheme.typography.title3)
                Text(st.summary, fontSize = 11.sp, textAlign = TextAlign.Center)
                CompactChip(onClick = { runner.dismiss() }, label = { Text("Close") })
            }
            is StudyScreen.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text("Problem", color = Color(0xFFEF5350), style = MaterialTheme.typography.title3)
                Text(st.msg, fontSize = 11.sp, textAlign = TextAlign.Center)
                CompactChip(onClick = { runner.dismiss() }, label = { Text("Close") })
            }
        }
    }
}

@Composable
private fun Msg(t: String) = Text(t, textAlign = TextAlign.Center, color = Grey)

@Composable
private fun StudyStart(runner: StudyRunner) {
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Attention study", style = MaterialTheme.typography.title3, color = Cyan) }
        item {
            Text("13 min: rest, Stroop, rest, CPT, rest. Mood questions before and after. Sit at a table, watch snug on the wrist, " +
                "keep the screen facing you.", fontSize = 11.sp, textAlign = TextAlign.Center, color = Grey)
        }
        item {
            Chip(onClick = { runner.start("ATTENTION_V1") }, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.primaryChipColors(), label = { Text("Start study") })
        }
        item {
            Chip(onClick = { runner.start("ATTENTION_DEMO") }, modifier = Modifier.fillMaxWidth(),
                colors = ChipDefaults.secondaryChipColors(), label = { Text("Demo (1 min)") },
                secondaryLabel = { Text("not for analysis") })
        }
    }
}

@Composable
private fun EmaScreen(s: StudyScreen.Ema, runner: StudyRunner) {
    var v by remember(s.stage, s.index) { mutableIntStateOf(5) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${if (s.stage == "PRE") "Before" else "After"}  ${s.index + 1}/${s.total}", fontSize = 11.sp, color = Grey)
        Text(s.item.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.title3, color = Cyan)
        Text("0 = not at all   10 = extremely", fontSize = 9.sp, color = Grey)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactChip(onClick = { v = (v - 1).coerceAtLeast(0) }, label = { Text("-", fontSize = 18.sp) })
            Text("$v", style = MaterialTheme.typography.display1, modifier = Modifier.testTag("ema_value"))
            CompactChip(onClick = { v = (v + 1).coerceAtMost(10) }, label = { Text("+", fontSize = 18.sp) })
        }
        Button(onClick = { runner.emaSubmit(v) }) { Text("Next") }
    }
}

@Composable
private fun StroopScreen(s: StudyScreen.Stroop, runner: StudyRunner) {
    if (s.trial != null) LaunchedEffect(s.id) { withFrameNanos { }; runner.stimulusShown(s.id) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(width = 150.dp, height = 70.dp), contentAlignment = Alignment.Center) {
            s.trial?.let {
                Text(it.word.name, color = Color(it.ink.argb), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
        }
        // answer = the INK colour. Fixed positions, no labels (so the buttons themselves carry no word).
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StroopButton(StroopColor.RED, runner); StroopButton(StroopColor.GREEN, runner)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
            StroopButton(StroopColor.BLUE, runner); StroopButton(StroopColor.YELLOW, runner)
        }
    }
}

@Composable
private fun StroopButton(c: StroopColor, runner: StudyRunner) {
    Box(
        Modifier.size(58.dp).clip(CircleShape).background(Color(c.argb)).testTag("stroop_${c.name}")
            .pointerInput(c) { detectTapGestures(onPress = { runner.stroopAnswer(c) }) },
    )
}

@Composable
private fun CptScreen(s: StudyScreen.Cpt, runner: StudyRunner) {
    if (s.letter != null) LaunchedEffect(s.id) { withFrameNanos { }; runner.stimulusShown(s.id) }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onPress = { runner.cptTap() }) }.testTag("cpt_area"),
        contentAlignment = Alignment.Center,
    ) {
        if (s.letter != null) Text(s.letter.toString(), fontSize = 96.sp, fontWeight = FontWeight.Bold)
        else Text("+", fontSize = 40.sp, color = Color(0xFF37474F))
    }
}
