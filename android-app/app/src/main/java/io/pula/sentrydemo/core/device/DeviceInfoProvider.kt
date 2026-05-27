package io.pula.sentrydemo.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import io.pula.sentrydemo.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class StaticInfo(
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
        val appVersionName: String,
        val appVersionCode: Long,
        val abi: String,
    )

    data class RuntimeSnapshot(
        val freeRamMb: Long,
        val totalRamMb: Long,
        val freeStorageMb: Long,
        val networkSpeedMbps: Int,
        val networkTransport: String,
        val batteryPct: Int,
    )

    fun staticInfo(): StaticInfo = StaticInfo(
        manufacturer = Build.MANUFACTURER ?: "unknown",
        model = Build.MODEL ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        appVersionName = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        abi = (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
    )

    fun runtimeSnapshot(): RuntimeSnapshot {
        val mem = ActivityManager.MemoryInfo().also {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(it)
        }
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        return RuntimeSnapshot(
            freeRamMb = mem.availMem / 1_048_576L,
            totalRamMb = mem.totalMem / 1_048_576L,
            freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / 1_048_576L,
            networkSpeedMbps = caps?.linkDownstreamBandwidthKbps?.div(1000) ?: 0,
            networkTransport = describeTransport(caps),
            batteryPct = readBatteryPct(),
        )
    }

    private fun describeTransport(caps: NetworkCapabilities?): String = when {
        caps == null -> "none"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }

    private fun readBatteryPct(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }
}
