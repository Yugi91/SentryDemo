package io.pula.sentrydemo.domain.usecase

import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import javax.inject.Inject

class TriggerAnrUseCase @Inject constructor(
    private val contextEnricher: SentryContextEnricher,
) {
    /**
     * Blocks the main thread for [blockMs]. Must be invoked on the main thread.
     * Sentry's watchdog (default 5 s) will report an `ApplicationNotResponding`
     * event with a full main-thread stack trace.
     */
    operator fun invoke(blockMs: Long = 8_000L) {
        contextEnricher.enrich(actionName = "anr_action")
        val end = System.currentTimeMillis() + blockMs
        // Busy-wait keeps the thread "running" so the watchdog sees it as stuck
        // exactly like a real ANR (vs. Thread.sleep, which is detectable as parked).
        @Suppress("ControlFlowWithEmptyBody")
        while (System.currentTimeMillis() < end) { /* spin */ }
    }
}
