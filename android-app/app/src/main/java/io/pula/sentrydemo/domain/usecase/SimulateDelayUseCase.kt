package io.pula.sentrydemo.domain.usecase

import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import io.sentry.Sentry
import io.sentry.SpanStatus
import kotlinx.coroutines.delay
import javax.inject.Inject

class SimulateDelayUseCase @Inject constructor(
    private val contextEnricher: SentryContextEnricher,
) {
    suspend operator fun invoke(durationMs: Long = 1_500L) {
        contextEnricher.enrich(actionName = "delay_action")

        val tx = Sentry.startTransaction("delay_action", "task")
        try {
            val span = tx.startChild("processing", "simulated long-running work")
            span.setData("duration_ms", durationMs)
            try {
                delay(durationMs)
                span.finish(SpanStatus.OK)
            } catch (t: Throwable) {
                span.throwable = t
                span.finish(SpanStatus.INTERNAL_ERROR)
                throw t
            }
        } finally {
            tx.finish()
        }
    }
}
