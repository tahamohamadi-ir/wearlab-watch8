package com.wearlab.watch.sensor.samsung

import android.content.Context
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** One output row of a DataPoint; HR/IBI turns one DataPoint into one row per heartbeat. */
class Row(val values: DoubleArray, val quality: Int? = null, val tsShiftNs: Long = 0)

/**
 * One adapter per Samsung tracker. DataPoint.timestamp is epoch milliseconds, so UTC is exact
 * and the monotonic time is derived from it at delivery. The tracker's own status code is stored
 * in the `quality` column so bad samples are flagged, never silently dropped (section 61.3).
 */
class SamsungTrackerAdapter(
    private val context: Context,
    override val type: SensorType,
    private val tracker: HealthTrackerType,
    private val ppgTypes: Set<PpgType>?,
    override val channels: List<String>,
    private val plotIndex: Int = 0,
    private val extract: (DataPoint) -> List<Row>,
) : SensorAdapter {

    override fun plotValue(values: DoubleArray) = values.getOrElse(plotIndex) { Double.NaN }

    override suspend fun availability(): Availability {
        val r = SamsungHealth.supportedTypes(context)
        r.exceptionOrNull()?.let { return Availability.Unavailable(it.message ?: "SDK not connected") }
        return if (tracker in r.getOrThrow()) Availability.Available
        else Availability.Unavailable("$tracker not in trackingCapability")
    }

    override fun samples(config: SensorConfig): Flow<SensorSample> = callbackFlow {
        val svc = SamsungHealth.connect(context)
        val t = if (ppgTypes != null) svc.getHealthTracker(tracker, ppgTypes) else svc.getHealthTracker(tracker)
        var seq = 0L
        var lastTs = 0L
        t.setEventListener(object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(list: List<DataPoint>) {
                val nowMono = Timebase.monotonicNs()
                val nowUtc = Timebase.utcNs()
                for (dp in list) for (row in extract(dp)) {
                    // Shifted per-beat timestamps must stay monotonic.
                    val utc = maxOf(lastTs, dp.timestamp * 1_000_000L + row.tsShiftNs)
                    lastTs = utc
                    trySend(
                        SensorSample(
                            type, seq++, utc, Timebase.utcToMonoNs(utc, nowMono, nowUtc), utc,
                            channels, row.values, row.quality,
                        ),
                    )
                }
            }

            override fun onFlushCompleted() {}

            override fun onError(error: HealthTracker.TrackerError) {
                close(IllegalStateException("Tracker error: $error"))
            }
        })
        awaitClose { t.unsetEventListener() }
    }.buffer(Channel.UNLIMITED) // never drop at the adapter: SDK delivers bursts larger than the default 64 slots

    companion object {
        private fun d(v: Number?) = v?.toDouble() ?: Double.NaN
        private fun one(vararg v: Double, q: Int? = null) = listOf(Row(v, q))

        fun ppg(c: Context) = SamsungTrackerAdapter(
            c, SensorType.PPG, HealthTrackerType.PPG_CONTINUOUS,
            setOf(PpgType.GREEN, PpgType.IR, PpgType.RED),
            listOf("green", "green_status", "ir", "ir_status", "red", "red_status"), plotIndex = 2,
        ) {
            one(
                d(it.getValue(ValueKey.PpgSet.PPG_GREEN)), d(it.getValue(ValueKey.PpgSet.GREEN_STATUS)),
                d(it.getValue(ValueKey.PpgSet.PPG_IR)), d(it.getValue(ValueKey.PpgSet.IR_STATUS)),
                d(it.getValue(ValueKey.PpgSet.PPG_RED)), d(it.getValue(ValueKey.PpgSet.RED_STATUS)),
                q = it.getValue(ValueKey.PpgSet.GREEN_STATUS),
            )
        }

        fun eda(c: Context) = SamsungTrackerAdapter(
            c, SensorType.EDA, HealthTrackerType.EDA_CONTINUOUS, null, listOf("skin_conductance_uS", "status"),
        ) {
            val st = it.getValue(ValueKey.EdaSet.STATUS)
            one(d(it.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)), d(st), q = st)
        }

        /**
         * One row per heartbeat (the SDK delivers 0..n IBIs per second; keeping only the last lost data).
         * Beat k is timestamped dp.ts minus the sum of the later IBIs - an estimate, since the SDK only
         * gives one timestamp per batch. `ibi_n` is the batch size and `ibi_index` the beat position.
         */
        fun hrIbi(c: Context) = SamsungTrackerAdapter(
            c, SensorType.HR_IBI, HealthTrackerType.HEART_RATE_CONTINUOUS, null,
            listOf("hr", "hr_status", "ibi_ms", "ibi_status", "ibi_index", "ibi_n"),
        ) {
            val hr = d(it.getValue(ValueKey.HeartRateSet.HEART_RATE))
            val hrSt = it.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
            val ibi = it.getValue(ValueKey.HeartRateSet.IBI_LIST).orEmpty()
            val ibiSt = it.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST).orEmpty()
            if (ibi.isEmpty()) one(hr, d(hrSt), Double.NaN, Double.NaN, -1.0, 0.0, q = hrSt)
            else ibi.mapIndexed { k, v ->
                val later = ibi.drop(k + 1).sum().toLong()
                Row(
                    doubleArrayOf(hr, d(hrSt), v.toDouble(), d(ibiSt.getOrNull(k)), k.toDouble(), ibi.size.toDouble()),
                    hrSt, -later * 1_000_000L,
                )
            }
        }

        private fun skin(c: Context, type: SensorType, tracker: HealthTrackerType) = SamsungTrackerAdapter(
            c, type, tracker, null, listOf("object_c", "ambient_c"),
        ) {
            one(
                d(it.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)),
                d(it.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)),
            )
        }

        /** The SDK emits one sample per ~60 s in this mode. */
        fun skinTemp(c: Context) = skin(c, SensorType.SKIN_TEMP, HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)

        /** Spot measurement within seconds of starting - suited to short periodic windows. */
        fun skinTempSpot(c: Context) = skin(c, SensorType.SKIN_TEMP_OD, HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)

        /** On-demand: needs a finger on the electrode; lead_off reports contact. */
        fun ecg(c: Context) = SamsungTrackerAdapter(
            c, SensorType.ECG, HealthTrackerType.ECG_ON_DEMAND, null, listOf("ecg_mv", "lead_off", "sequence"),
        ) {
            val lead = it.getValue(ValueKey.EcgSet.LEAD_OFF)
            one(d(it.getValue(ValueKey.EcgSet.ECG_MV)), d(lead), d(it.getValue(ValueKey.EcgSet.SEQUENCE)), q = lead)
        }

        /** Spot SpO2 (~30 s, arm still). status/accuracy flags are kept as raw channels. */
        fun spo2(c: Context) = SamsungTrackerAdapter(
            c, SensorType.SPO2, HealthTrackerType.SPO2_ON_DEMAND, null,
            listOf("spo2_pct", "hr", "status", "accuracy_flag"), plotIndex = 0,
        ) {
            val st = it.getValue(ValueKey.SpO2Set.STATUS)
            one(
                d(it.getValue(ValueKey.SpO2Set.SPO2)), d(it.getValue(ValueKey.SpO2Set.HEART_RATE)), d(st),
                d(it.getValue(ValueKey.SpO2Set.ACCURACY_FLAG)), q = st,
            )
        }

        /** Multi-frequency bioimpedance, fingers on both buttons. Magnitude (ohm) + phase per frequency. */
        fun mfBia(c: Context) = SamsungTrackerAdapter(
            c, SensorType.MF_BIA, HealthTrackerType.MF_BIA_ON_DEMAND, null,
            listOf(
                "mag_5k", "phase_5k", "mag_10k", "phase_10k", "mag_50k", "phase_50k", "mag_250k", "phase_250k",
                "status", "progress",
            ),
        ) {
            val st = it.getValue(ValueKey.MfBiaSet.STATUS)
            one(
                d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K)), d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_5K)),
                d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_10K)), d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_10K)),
                d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K)), d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K)),
                d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K)), d(it.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_250K)),
                d(st), d(it.getValue(ValueKey.MfBiaSet.PROGRESS)), q = st,
            )
        }
    }
}
