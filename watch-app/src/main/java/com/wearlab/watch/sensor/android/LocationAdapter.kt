package com.wearlab.watch.sensor.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.wearlab.watch.sensor.api.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Raw GPS fixes from the watch's own GNSS (GPS provider only; no network location, no phone).
 * Location is sensitive: it is only recorded when the protocol lists LOCATION, in its own file
 * (location.csv). `quality` stores the horizontal accuracy in metres (rounded) of the fix.
 */
class LocationAdapter(private val context: Context) : SensorAdapter {
    override val type = SensorType.LOCATION
    override val channels = listOf("lat_deg", "lon_deg", "alt_m", "accuracy_m", "speed_mps", "bearing_deg")
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Accuracy in metres: the sparkline shows the fix converging. */
    override fun plotValue(values: DoubleArray) = values[3]

    override suspend fun availability(): Availability = when {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
            Availability.Unavailable("location permission not granted")
        !lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> Availability.Unavailable("no GPS provider")
        !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> Availability.Unavailable("GPS is off in settings")
        else -> Availability.Available
    }

    @Suppress("MissingPermission")
    override fun samples(config: SensorConfig): Flow<SensorSample> = callbackFlow {
        var seq = 0L
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val utc = loc.time * 1_000_000L
                trySend(
                    SensorSample(
                        type, seq++, utc, loc.elapsedRealtimeNanos, utc, channels,
                        doubleArrayOf(
                            loc.latitude, loc.longitude,
                            if (loc.hasAltitude()) loc.altitude else Double.NaN,
                            if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN,
                            if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN,
                            if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN,
                        ),
                        if (loc.hasAccuracy()) loc.accuracy.toInt() else null,
                    ),
                )
            }

            override fun onProviderDisabled(provider: String) {
                close(IllegalStateException("GPS provider disabled"))
            }
        }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(l) }
    }.buffer(Channel.UNLIMITED)
}
