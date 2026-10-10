package com.wearlab.watch.core.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

object DeviceInfo {
    private fun sysProp(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun pkgVersion(c: Context, pkg: String): String? =
        runCatching { c.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()

    fun batteryPercent(c: Context): Int? {
        val i: Intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    fun toJson(c: Context): JSONObject = JSONObject().apply {
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android_release", Build.VERSION.RELEASE)
        put("sdk_int", Build.VERSION.SDK_INT)
        put("build_display", Build.DISPLAY)
        put("fingerprint", Build.FINGERPRINT)
        put("csc_sales_code", sysProp("ro.csc.sales_code") ?: JSONObject.NULL)
        put("csc_country", sysProp("ro.csc.country_code") ?: JSONObject.NULL)
        put("one_ui_watch_version", sysProp("ro.build.version.oneui") ?: JSONObject.NULL)
        put("health_platform_version", pkgVersion(c, "com.samsung.android.service.health") ?: JSONObject.NULL)
        put("samsung_health_version", pkgVersion(c, "com.sec.android.app.shealth") ?: JSONObject.NULL)
        put("battery_percent", batteryPercent(c) ?: JSONObject.NULL)
        put("app_version", pkgVersion(c, c.packageName) ?: JSONObject.NULL)
    }
}
