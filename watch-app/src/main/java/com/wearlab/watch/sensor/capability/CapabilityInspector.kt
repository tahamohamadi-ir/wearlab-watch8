package com.wearlab.watch.sensor.capability

import android.content.Context
import android.hardware.SensorManager
import com.wearlab.watch.core.diagnostics.DeviceInfo
import com.wearlab.watch.core.time.Timebase
import com.wearlab.watch.sensor.api.Availability
import com.wearlab.watch.sensor.api.SensorAdapter
import com.wearlab.watch.sensor.api.SensorType
import com.wearlab.watch.sensor.samsung.SamsungHealth
import org.json.JSONArray
import org.json.JSONObject

/** M01: builds the Device Capability Report from what the device actually reports. */
object CapabilityInspector {

    suspend fun availabilityMap(adapters: List<SensorAdapter>): Map<SensorType, Availability> =
        adapters.associate { it.type to it.availability() }

    suspend fun report(
        context: Context,
        adapters: List<SensorAdapter>,
        permissions: Map<String, Boolean>,
    ): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val samsung = SamsungHealth.supportedTypes(context)
        return JSONObject().apply {
            put("report_version", 1)
            put("generated_utc_ns", Timebase.utcNs().toString())
            put("device", DeviceInfo.toJson(context))
            put("permissions", JSONObject(permissions))
            put("samsung_sdk", JSONObject().apply {
                put("connected", samsung.isSuccess)
                put("error", samsung.exceptionOrNull()?.message ?: JSONObject.NULL)
                put("capability_version", SamsungHealth.sdkCapabilityVersion(context) ?: JSONObject.NULL)
                put("supported_tracker_types", JSONArray(samsung.getOrNull().orEmpty().map { it.name }))
            })
            put("android_sensors", JSONArray(sm.getSensorList(android.hardware.Sensor.TYPE_ALL).map {
                JSONObject().apply {
                    put("name", it.name); put("type", it.type); put("string_type", it.stringType)
                    put("vendor", it.vendor); put("min_delay_us", it.minDelay)
                    put("max_range", it.maximumRange.toDouble()); put("resolution", it.resolution.toDouble())
                }
            }))
            put("sensors", JSONObject().apply {
                for ((type, a) in availabilityMap(adapters)) {
                    put(type.name, JSONObject().apply {
                        when (a) {
                            Availability.Available -> put("state", "AVAILABLE")
                            is Availability.Unavailable -> { put("state", "UNAVAILABLE"); put("reason", a.reason) }
                        }
                    })
                }
            })
        }
    }
}
