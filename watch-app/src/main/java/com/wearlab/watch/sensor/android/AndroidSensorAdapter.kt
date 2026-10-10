package com.wearlab.watch.sensor.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Any platform SensorManager sensor. event.timestamp is on the elapsedRealtime clock, so the
 * monotonic time is exact and UTC is derived. `quality` carries SensorEvent.accuracy (0..3).
 * Continuous sensors run at min(requested rate, hardware max); on-change sensors (light, off-body,
 * step counter) ignore the rate and report whenever the value changes.
 */
class AndroidSensorAdapter(
    context: Context,
    override val type: SensorType,
    private val androidType: Int,
    override val channels: List<String>,
    private val onChange: Boolean = false,
    private val plot: (DoubleArray) -> Double = { v -> v.firstOrNull() ?: Double.NaN },
) : SensorAdapter {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    override fun plotValue(values: DoubleArray) = plot(values)

    override suspend fun availability(): Availability =
        if (sm.getDefaultSensor(androidType) != null) Availability.Available
        else Availability.Unavailable("no default sensor")

    /** Recorded in metadata.json so every dataset states exactly which chip produced it. */
    override fun hardwareInfo(): Map<String, Any?> = sm.getDefaultSensor(androidType)?.let {
        mapOf(
            "name" to it.name, "vendor" to it.vendor, "android_type" to it.stringType,
            "max_rate_hz" to if (it.minDelay > 0) 1_000_000.0 / it.minDelay else null,
            "resolution" to it.resolution.toDouble(), "max_range" to it.maximumRange.toDouble(),
            "power_mA" to it.power.toDouble(), "fifo_max" to it.fifoMaxEventCount,
        )
    } ?: emptyMap()

    override fun samples(config: SensorConfig): Flow<SensorSample> = callbackFlow {
        val s = sm.getDefaultSensor(androidType) ?: throw IllegalStateException("sensor not present")
        var seq = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                trySend(
                    SensorSample(
                        type, seq++, e.timestamp, e.timestamp,
                        Timebase.monoToUtcNs(e.timestamp), channels,
                        DoubleArray(channels.size) { e.values.getOrElse(it) { Float.NaN }.clean() }, e.accuracy,
                    ),
                )
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        val ok = if (onChange) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL) else {
            val hwMax = if (s.minDelay > 0) 1_000_000f / s.minDelay else config.rateHz
            sm.registerListener(l, s, (1_000_000f / minOf(config.rateHz, hwMax)).toInt())
        }
        if (!ok) throw IllegalStateException("registerListener failed (permission?)")
        awaitClose { sm.unregisterListener(l) }
    }.buffer(Channel.UNLIMITED)

    companion object {
        private fun mag3(v: DoubleArray) = kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

        fun acc(c: Context) = AndroidSensorAdapter(c, SensorType.ACC, Sensor.TYPE_ACCELEROMETER, listOf("x", "y", "z"), plot = ::mag3)
        fun gyro(c: Context) = AndroidSensorAdapter(c, SensorType.GYRO, Sensor.TYPE_GYROSCOPE, listOf("x", "y", "z"), plot = ::mag3)
        fun magnetometer(c: Context) = AndroidSensorAdapter(c, SensorType.MAG, Sensor.TYPE_MAGNETIC_FIELD, listOf("x_uT", "y_uT", "z_uT"), plot = ::mag3)
        fun barometer(c: Context) = AndroidSensorAdapter(c, SensorType.BARO, Sensor.TYPE_PRESSURE, listOf("pressure_hPa"))
        fun light(c: Context) = AndroidSensorAdapter(c, SensorType.LIGHT, Sensor.TYPE_LIGHT, listOf("lux"), onChange = true)

        /** 1 = on wrist, 0 = off wrist. The key context for every physiological status code. */
        fun offBody(c: Context) = AndroidSensorAdapter(
            c, SensorType.OFFBODY, Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, listOf("on_body"), onChange = true,
        )

        /** Cumulative steps since boot. */
        fun steps(c: Context) = AndroidSensorAdapter(c, SensorType.STEPS, Sensor.TYPE_STEP_COUNTER, listOf("steps_since_boot"), onChange = true)

        /** Orientation as a quaternion (x, y, z, w) + heading accuracy: posture / wrist angle. */
        fun rotation(c: Context) = AndroidSensorAdapter(
            c, SensorType.ROTATION, Sensor.TYPE_ROTATION_VECTOR, listOf("qx", "qy", "qz", "qw", "heading_acc_rad"),
            plot = { it[3] },
        )
    }
}
