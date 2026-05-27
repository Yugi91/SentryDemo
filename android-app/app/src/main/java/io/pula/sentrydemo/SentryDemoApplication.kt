package io.pula.sentrydemo

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import io.sentry.android.core.SentryAndroid
import javax.inject.Inject

@HiltAndroidApp
class SentryDemoApplication : Application() {

    @Inject lateinit var contextEnricher: SentryContextEnricher

    override fun onCreate() {
        // Initialize Sentry BEFORE Hilt so the uncaught-exception handler and
        // ANR watchdog cover failures during DI setup as well.
        if (BuildConfig.SENTRY_DSN.isBlank()) {
            Log.w("SentryDemo", "sentry.dsn not set in local.properties — Sentry will be disabled.")
        }

        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.environment = BuildConfig.SENTRY_ENV
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
            options.isDebug = BuildConfig.DEBUG

            // Performance — sample everything for the demo; dial down for prod
            options.tracesSampleRate = 1.0
            options.profilesSampleRate = 1.0

            // ANR — default 5 s watchdog, we want it for the demo button
            options.isAnrEnabled = true
            options.anrTimeoutIntervalMillis = 5_000L
            options.isAnrReportInDebug = true

            // Threads & screenshots
            options.isAttachThreads = true
            options.isAttachScreenshot = false  // privacy default
            options.isAttachViewHierarchy = false

            options.isSendDefaultPii = true
            options.isEnableAutoActivityLifecycleTracing = true
            options.isEnableUserInteractionTracing = true
        }

        super.onCreate()
        // Static device + user fields once. Volatile fields (free RAM etc.) are
        // refreshed per-action via SentryContextEnricher.enrich(actionName).
        contextEnricher.installOnce()
    }
}
