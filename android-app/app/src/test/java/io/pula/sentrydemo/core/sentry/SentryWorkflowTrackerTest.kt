package io.pula.sentrydemo.core.sentry

import com.google.common.truth.Truth.assertThat
import io.pula.sentrydemo.testing.SentryCaptureRule
import io.sentry.SentryLevel
import io.sentry.SpanStatus
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SentryWorkflowTrackerTest {

    @get:Rule val sentry = SentryCaptureRule()

    private val tracker = SentryWorkflowTracker()

    @Test
    fun `runRoot emits one transaction with each named step as a child span`() = runTest {
        tracker.runRoot("photo_workflow", "task") {
            step("capture_image") {
                data("filename", "img.jpg")
                data("size_bytes", 1_234_567L)
            }
            step("save_image") {
                data("storage_path", "/sdcard/img.jpg")
            }
            step("sync_image") {
                data("upload_url", "https://cdn.example/img.jpg")
            }
        }

        assertThat(sentry.transactions).hasSize(1)
        val tx = sentry.transactions.single()
        assertThat(tx.transaction).isEqualTo("photo_workflow")

        val stepNames = tx.spans.map { it.op }
        assertThat(stepNames).containsExactly("capture_image", "save_image", "sync_image").inOrder()

        val captureSpan = tx.spans.first { it.op == "capture_image" }
        assertThat(captureSpan.data).containsEntry("filename", "img.jpg")
        assertThat(captureSpan.data).containsEntry("size_bytes", 1_234_567L)
        assertThat(captureSpan.status).isEqualTo(SpanStatus.OK)
    }

    @Test
    fun `step failure marks span internal_error and attaches throwable`() = runTest {
        var caught: Throwable? = null
        try {
            tracker.runRoot("photo_workflow", "task") {
                step("capture_image") { data("filename", "img.jpg") }
                step("sync_image") {
                    data("upload_endpoint", "https://api.example/upload")
                    throw IllegalStateException("simulated 500")
                }
            }
        } catch (t: Throwable) {
            caught = t
        }

        // Tracker re-throws the original exception
        assertThat(caught).isInstanceOf(IllegalStateException::class.java)
        assertThat(caught).hasMessageThat().isEqualTo("simulated 500")

        val tx = sentry.transactions.single()
        val syncSpan = tx.spans.first { it.op == "sync_image" }
        assertThat(syncSpan.status).isEqualTo(SpanStatus.INTERNAL_ERROR)

        // And the exception was reported as an error event
        assertThat(sentry.events).hasSize(1)
        assertThat(sentry.events.single().throwable).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `markFailed makes the span report internal_error without throwing`() = runTest {
        tracker.runRoot("photo_workflow", "task") {
            step("sync_image") {
                data("upload_endpoint", "https://api.example/upload")
                markFailed("upstream_500")
            }
        }

        val tx = sentry.transactions.single()
        val span = tx.spans.single()
        assertThat(span.status).isEqualTo(SpanStatus.INTERNAL_ERROR)
        assertThat(span.data).containsEntry("error_reason", "upstream_500")
        // No exception event since no throw
        assertThat(sentry.events).isEmpty()
    }

    @Test
    fun `runRoot emits start and finish breadcrumbs for the workflow and each step`() = runTest {
        tracker.runRoot("photo_workflow", "task") {
            step("capture_image") { /* no-op */ }
        }

        val messages = sentry.breadcrumbs.map { it.message }
        assertThat(messages).containsAtLeast(
            "workflow.started",
            "capture_image.started",
            "capture_image.finished status=OK",
            "workflow.finished",
        ).inOrder()

        val levels = sentry.breadcrumbs.map { it.level }
        assertThat(levels).doesNotContain(SentryLevel.ERROR)
    }
}
