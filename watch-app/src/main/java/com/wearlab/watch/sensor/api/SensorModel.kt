package com.wearlab.watch.sensor.api

import kotlinx.coroutines.flow.Flow

enum class SensorType(val label: String) {
    ACC("Accelerometer"), GYRO("Gyroscope"),
    PPG("PPG (G/IR/R)"), EDA("EDA"), ECG("ECG"), HR_IBI("HR / IBI"),
    SKIN_TEMP("Skin temp (cont.)"), SKIN_TEMP_OD("Skin temp (spot)"),
    LOCATION("GPS"),
    MAG("Magnetometer"), BARO("Barometer"), LIGHT("Ambient light"), OFFBODY("Off-body"),
    STEPS("Step counter"), ROTATION("Orientation"), SYSTEM("Battery / system"),
    SPO2("SpO2 (spot)"), MF_BIA("MF-BIA (spot)");

    /** Needs the user (finger on electrodes / holding still): never part of an unattended session. */
    val manual get() = this == ECG || this == SPO2 || this == MF_BIA
}

/** Availability is always read from the device, never assumed (M01). */
sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String) : Availability
}

/** One normalised sample (§11). Timestamps in ns: source clock, monotonic, UTC. Doubles keep GPS precision. */
class SensorSample(
    val sensor: SensorType,
    val sequence: Long,
    val sourceTsNs: Long,
    val monoNs: Long,
    val utcNs: Long,
    val channels: List<String>,
    val values: DoubleArray,
    val quality: Int? = null,
)

data class SensorConfig(val rateHz: Float = 50f)

interface SensorAdapter {
    val type: SensorType
    val channels: List<String>

    /** A continuous tracker that delivers nothing for this long is considered stalled and is restarted. */
    val silenceTimeoutSec: Int? get() = null

    suspend fun availability(): Availability

    /** Chip/vendor details written into metadata.json; empty when unknown. */
    fun hardwareInfo(): Map<String, Any?> = emptyMap()

    /** Single number to draw in the live sparkline. */
    fun plotValue(values: DoubleArray): Double = values.firstOrNull() ?: Double.NaN

    /** Collecting starts the sensor; cancelling the collection stops it. Errors are thrown into the flow. */
    fun samples(config: SensorConfig): Flow<SensorSample>
}

/** float -> double without binary noise (0.1f must print as 0.1, not 0.10000000149). */
fun Float.clean(): Double = if (isNaN()) Double.NaN else toString().toDouble()
