package com.wearlab.watch.sensor.samsung

import android.content.Context
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import kotlinx.coroutines.CompletableDeferred

/** Process-wide connection to Samsung Health Sensor Service. */
object SamsungHealth {
    private var service: HealthTrackingService? = null
    private var pending: CompletableDeferred<HealthTrackingService>? = null

    /** Connects (or reuses). Throws with the SDK failure reason (e.g. PACKAGE_NOT_INSTALLED). */
    suspend fun connect(context: Context): HealthTrackingService {
        service?.let { return it }
        val d = pending ?: CompletableDeferred<HealthTrackingService>().also { d ->
            pending = d
            lateinit var svc: HealthTrackingService
            svc = HealthTrackingService(object : ConnectionListener {
                override fun onConnectionSuccess() {
                    service = svc
                    d.complete(svc)
                }

                override fun onConnectionEnded() {
                    service = null
                    pending = null
                }

                override fun onConnectionFailed(e: HealthTrackerException) {
                    pending = null
                    d.completeExceptionally(
                        IllegalStateException("Samsung SDK connection failed: code=${e.errorCode} ${e.message}"),
                    )
                }
            }, context.applicationContext)
            svc.connectService()
        }
        return d.await()
    }

    suspend fun supportedTypes(context: Context): Result<List<HealthTrackerType>> = runCatching {
        connect(context).trackingCapability.supportHealthTrackerTypes
    }

    suspend fun sdkCapabilityVersion(context: Context): String? =
        runCatching { connect(context).trackingCapability.version }.getOrNull()
}
