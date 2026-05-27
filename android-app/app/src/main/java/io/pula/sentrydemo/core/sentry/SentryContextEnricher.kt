package io.pula.sentrydemo.core.sentry

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.pula.sentrydemo.BuildConfig
import io.pula.sentrydemo.core.device.DeviceInfoProvider
import io.sentry.Sentry
import io.sentry.protocol.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stamps every outgoing event with rich device + user context.
 *
 * - [installOnce] sets static fields (user id, manufacturer, model, app version)
 *   on the global Sentry scope. Call once from `Application.onCreate`.
 * - [enrich] refreshes volatile fields (free RAM, free storage, network speed)
 *   and sets the `action_name` tag — call this right before each demo action so
 *   the captured event reflects state at the moment of the action.
 */
@Singleton
class SentryContextEnricher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfoProvider: DeviceInfoProvider,
) {
    fun installOnce() {
        val info = deviceInfoProvider.staticInfo()
        Sentry.configureScope { scope ->
            scope.user = User().apply { id = BuildConfig.DEMO_USER_ID }
            scope.setTag("device.manufacturer", info.manufacturer)
            scope.setTag("device.model", info.model)
            scope.setTag("app.version", info.appVersionName)
            scope.setTag("app.version_code", info.appVersionCode.toString())
            scope.setContexts(
                "device_static",
                mapOf(
                    "manufacturer" to info.manufacturer,
                    "model" to info.model,
                    "sdk_int" to info.sdkInt,
                    "app_version" to info.appVersionName,
                    "app_version_code" to info.appVersionCode,
                    "abi" to info.abi,
                ),
            )
        }
    }

    fun enrich(actionName: String) {
        val runtime = deviceInfoProvider.runtimeSnapshot()
        Sentry.configureScope { scope ->
            scope.setTag("action_name", actionName)
            scope.setContexts(
                "device_runtime",
                mapOf(
                    "free_ram_mb" to runtime.freeRamMb,
                    "total_ram_mb" to runtime.totalRamMb,
                    "free_storage_mb" to runtime.freeStorageMb,
                    "network_speed_mbps" to runtime.networkSpeedMbps,
                    "network_transport" to runtime.networkTransport,
                    "battery_pct" to runtime.batteryPct,
                ),
            )
        }
    }
}
