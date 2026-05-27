package io.pula.sentrydemo.domain.usecase

import com.google.common.truth.Truth.assertThat
import io.mockk.justRun
import io.mockk.mockk
import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import io.pula.sentrydemo.core.sentry.SentryWorkflowTracker
import io.pula.sentrydemo.data.repository.PhotoWorkflowRepositoryImpl
import io.pula.sentrydemo.domain.model.WorkflowStepResult
import io.pula.sentrydemo.testing.SentryCaptureRule
import io.sentry.SpanStatus
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RunPhotoWorkflowUseCaseTest {

    @get:Rule val sentry = SentryCaptureRule()

    private val tracker = SentryWorkflowTracker()
    private val repo = PhotoWorkflowRepositoryImpl()
    private val enricher = mockk<SentryContextEnricher>(relaxed = true).also {
        justRun { it.enrich(any()) }
    }
    private val useCase = RunPhotoWorkflowUseCase(repo, tracker, enricher)

    @Test
    fun `happy path produces a transaction with three OK spans and a report with no failures`() = runTest {
        // Repository sync has a 20% random failure baseline; retry until clean.
        var report: io.pula.sentrydemo.domain.model.WorkflowReport
        var attempt = 0
        do {
            sentry.transactions.clear()
            sentry.events.clear()
            sentry.breadcrumbs.clear()
            report = useCase(forceFailure = false)
            attempt++
        } while (!report.ok && attempt < 20)

        assertThat(report.ok).isTrue()
        assertThat(report.steps.map { it.name }).containsExactly(
            "capture_image", "save_image", "sync_image",
        ).inOrder()
        assertThat(report.steps.map { it.status }).containsExactly(
            WorkflowStepResult.Status.OK,
            WorkflowStepResult.Status.OK,
            WorkflowStepResult.Status.OK,
        )

        val tx = sentry.transactions.first { it.transaction == "photo_workflow" }
        assertThat(tx.spans.map { it.op })
            .containsExactly("capture_image", "save_image", "sync_image").inOrder()
        assertThat(tx.spans.all { it.status == SpanStatus.OK }).isTrue()
    }

    @Test
    fun `forceFailure=true marks only sync_image as FAILED and captures the exception`() = runTest {
        val report = useCase(forceFailure = true)

        assertThat(report.ok).isFalse()
        // First two steps complete and are appended to the report; the third
        // throws and is recorded with FAILED status before re-throwing.
        assertThat(report.steps.map { it.name to it.status }).containsExactly(
            "capture_image" to WorkflowStepResult.Status.OK,
            "save_image" to WorkflowStepResult.Status.OK,
            "sync_image" to WorkflowStepResult.Status.FAILED,
        ).inOrder()

        val tx = sentry.transactions.first { it.transaction == "photo_workflow" }
        val statuses = tx.spans.associate { it.op to it.status }
        assertThat(statuses["capture_image"]).isEqualTo(SpanStatus.OK)
        assertThat(statuses["save_image"]).isEqualTo(SpanStatus.OK)
        assertThat(statuses["sync_image"]).isEqualTo(SpanStatus.INTERNAL_ERROR)

        // The PhotoSyncException was captured as an error event
        assertThat(sentry.events).isNotEmpty()
        assertThat(sentry.events.last().throwable?.message).contains("HTTP 500")
    }
}
