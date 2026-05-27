package io.pula.sentrydemo.domain.usecase

import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import javax.inject.Inject

class TriggerCrashUseCase @Inject constructor(
    private val contextEnricher: SentryContextEnricher,
) {
    operator fun invoke(): Nothing {
        contextEnricher.enrich(actionName = "crash_action")
        // Sentry's uncaught-exception handler will catch this and ship the event
        // before the process dies. The next launch flushes the queued event.
        throw RuntimeException("Demo crash triggered from UI at ${System.currentTimeMillis()}")
    }
}
