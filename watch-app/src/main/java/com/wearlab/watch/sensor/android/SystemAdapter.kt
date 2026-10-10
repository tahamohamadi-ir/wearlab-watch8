package com.wearlab.watch.sensor.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Device telemetry sampled every [periodMs]: battery level/temperature/voltage, charging, thermal
 * status and screen state. Gives battery cost per sensor-hour (M11) and explains data artefacts
 * (a hot watch, a charging watch, a dozing watch) without guessing.
 */
class SystemAdapter(private val context: Context, private val periodMs: Long = 10_000) : SensorAdapter {
    override val type = SensorType.SYSTEM
    override val channels = listOf("battery_pct", "battery_temp_c", "voltage_mv", "charging", "thermal_status", "screen_interactive")

    override suspend fun availability(): Availability = Availability.Available

    override fun plotValue(values: DoubleArray) = values[0]

    override fun samples(config: SensorConfig): Flow<SensorSample> = flow {
        val pm = context.getSystemService(PowerManager::class.java)
        var seq = 0L
        while (true) {
            val b: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val mono = Timebase.monotonicNs()
            val utc = Timebase.utcNs()
            emit(
                SensorSample(
                    type, seq++, utc, mono, utc, channels,
                    doubleArrayOf(
                        if (level >= 0 && scale > 0) level * 100.0 / scale else Double.NaN,
                        (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
                            .let { if (it == Int.MIN_VALUE) Double.NaN else it / 10.0 },
                        (b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1).let { if (it < 0) Double.NaN else it.toDouble() },
                        if ((b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) 1.0 else 0.0,
                        pm.currentThermalStatus.toDouble(),
                        if (pm.isInteractive) 1.0 else 0.0,
                    ),
                ),
            )
            delay(periodMs)
        }
    }
}
