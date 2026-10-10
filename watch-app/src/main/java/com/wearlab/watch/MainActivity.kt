package com.wearlab.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.wearlab.watch.core.diagnostics.DeviceInfo
import com.wearlab.watch.sensor.api.Availability
import com.wearlab.watch.sensor.api.SensorType
import com.wearlab.watch.sensor.capability.CapabilityInspector
import com.wearlab.watch.session.ScheduleConfig
import com.wearlab.watch.session.ScheduleStore
import com.wearlab.watch.session.SessionManager
import com.wearlab.watch.session.SessionState
import com.wearlab.watch.storage.SessionStore
import com.wearlab.watch.storage.SessionSummary
import com.wearlab.watch.ui.AppActions
import com.wearlab.watch.ui.AppState
import com.wearlab.watch.ui.WearLabApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val wanted = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_SKIN_TEMPERATURE",
    )

    private var availability by mutableStateOf<Map<SensorType, Availability>?>(null)
    private var exportStatus by mutableStateOf<String?>(null)
    private lateinit var store: SessionStore
    private var sessionList by mutableStateOf(emptyList<SessionSummary>())
    private var schedule by mutableStateOf<ScheduleConfig?>(null)
    private var syncStatus by mutableStateOf<String?>(null)
    private var syncing by mutableStateOf(false)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scan()
    }

    private fun permissionState() = wanted.associateWith {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun scan() {
        availability = null
        lifecycleScope.launch {
            availability = CapabilityInspector.availabilityMap(InspectorRuntime.adapters(this@MainActivity))
        }
    }

    private fun defaults(name: String) = ScheduleConfig.from(store.loadProtocol(name))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        val mgr = InspectorRuntime.sessions ?: SessionManager(applicationContext, store).also { InspectorRuntime.sessions = it }
        // Automation hook for tests: `am start ... --es start_study ATTENTION_DEMO` opens the study screen and runs it.
        val startStudy = intent.getStringExtra("start_study")
        val startRoute = if (startStudy != null) "study" else "home"
        val study = com.wearlab.watch.study.StudyRunner(applicationContext, store, mgr) { InspectorRuntime.adapters(this) }
        schedule = ScheduleStore.load(this, defaults("BASELINE_REST_V2"))
        startStudy?.let { study.start(it) }
        lifecycleScope.launch(Dispatchers.IO) {
            store.recoverInterrupted()
            sessionList = store.list()
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray()) else scan()

        val actions = AppActions(
            onStartSensor = { a, c -> InspectorRuntime.start(this, a, c) },
            onStopSensor = { InspectorRuntime.stop(it.type) },
            onRescan = { scan() },
            onExport = {
                lifecycleScope.launch {
                    exportStatus = runCatching {
                        InspectorRuntime.exportReport(this@MainActivity, permissionState()).name
                    }.fold({ "Saved: $it" }, { "Export failed: ${it.message}" })
                }
            },
            onStartSession = { schedule?.let { mgr.start(it, InspectorRuntime.adapters(this)) } },
            onStopSession = { mgr.requestStop() },
            onMark = { mgr.mark() },
            onSchedule = { schedule = it; ScheduleStore.save(this, it) },
            onReloadSessions = { sessionList = store.list() },
            onSyncPhone = {
                if (!syncing) lifecycleScope.launch {
                    syncing = true
                    syncStatus = "connecting..."
                    syncStatus = runCatching {
                        com.wearlab.watch.transfer.PhoneSync.syncAll(this@MainActivity, store) { syncStatus = it }.message
                    }.getOrElse { "failed: ${it.message}" }
                    sessionList = store.list()
                    syncing = false
                }
            },
        )

        setContent {
            val runs by InspectorRuntime.state.collectAsState()
            val sui by mgr.ui.collectAsState()
            // Refresh the saved-session list when a session finishes.
            LaunchedEffect(sui.state) { sessionList = store.list() }
            // Display on while a capture window runs (the activity flag works where a screen wake lock does not).
            val busy = sui.state in setOf(SessionState.STARTING, SessionState.RUNNING)
            LaunchedEffect(busy, schedule?.keepScreenOn, sui.phase) {
                if (busy && schedule?.keepScreenOn == true && sui.phase != "WAITING") {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            schedule?.let { sch ->
                WearLabApp(
                    AppState(
                        adapters = InspectorRuntime.adapters(this), availability = availability, runs = runs,
                        session = sui, sessions = sessionList, schedule = sch,
                        protocols = store.protocolNames(), protocolDefaults = ::defaults,
                        battery = DeviceInfo.batteryPercent(this), exportStatus = exportStatus,
                        syncStatus = syncStatus, syncing = syncing,
                    ),
                    actions,
                    study,
                    startRoute,
                )
            }
        }
    }
}
