package io.pula.sentrydemo.domain.usecase

import com.google.common.truth.Truth.assertThat
import io.mockk.justRun
import io.mockk.mockk
import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import io.pula.sentrydemo.testing.SentryCaptureRule
import io.sentry.SpanStatus
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SimulateDelayUseCaseTest {

    @get:Rule val sentry = SentryCaptureRule()

    private val enricher = mockk<SentryContextEnricher>(relaxed = true).also {
        justRun { it.enrich(any()) }
    }
    private val useCase = SimulateDelayUseCase(enricher)

    @Test
    fun `produces a delay_action transaction with a processing child span`() = runTest {
        useCase(durationMs = 1_000L)

        assertThat(sentry.transactions).hasSize(1)
        val tx = sentry.transactions.single()
        assertThat(tx.transaction).isEqualTo("delay_action")

        assertThat(tx.spans).hasSize(1)
        val span = tx.spans.single()
        assertThat(span.op).isEqualTo("processing")
        assertThat(span.data).containsEntry("duration_ms", 1_000L)
        assertThat(span.status).isEqualTo(SpanStatus.OK)
    }
}
