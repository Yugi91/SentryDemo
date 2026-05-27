package io.pula.sentrydemo.domain.usecase

import io.pula.sentrydemo.core.sentry.SentryContextEnricher
import io.pula.sentrydemo.core.sentry.SentryWorkflowTracker
import io.pula.sentrydemo.domain.model.WorkflowReport
import io.pula.sentrydemo.domain.model.WorkflowStepResult
import io.pula.sentrydemo.domain.repository.PhotoWorkflowRepository
import java.time.Instant
import javax.inject.Inject

class RunPhotoWorkflowUseCase @Inject constructor(
    private val repository: PhotoWorkflowRepository,
    private val workflowTracker: SentryWorkflowTracker,
    private val contextEnricher: SentryContextEnricher,
) {
    suspend operator fun invoke(forceFailure: Boolean = false): WorkflowReport {
        contextEnricher.enrich(actionName = "photo_workflow")

        val steps = mutableListOf<WorkflowStepResult>()
        var ok = true

        try {
            workflowTracker.runRoot(name = "photo_workflow", operation = "task") {
                val photo = step("capture_image") {
                    val started = Instant.now()
                    val p = repository.capture()
                    data("filename", p.filename)
                    data("size_bytes", p.sizeBytes)
                    data("width", p.width)
                    data("height", p.height)
                    steps += WorkflowStepResult(
                        name = "capture_image",
                        startedAt = started,
                        finishedAt = Instant.now(),
                        status = WorkflowStepResult.Status.OK,
                        data = mapOf(
                            "filename" to p.filename,
                            "size_bytes" to p.sizeBytes,
                        ),
                        errorMessage = null,
                    )
                    p
                }

                val saved = step("save_image") {
                    val started = Instant.now()
                    val s = repository.save(photo)
                    data("storage_path", s.storagePath)
                    data("storage_kind", "internal")
                    steps += WorkflowStepResult(
                        name = "save_image",
                        startedAt = started,
                        finishedAt = Instant.now(),
                        status = WorkflowStepResult.Status.OK,
                        data = mapOf("storage_path" to s.storagePath),
                        errorMessage = null,
                    )
                    s
                }

                step("sync_image") {
                    val started = Instant.now()
                    data("upload_endpoint", "https://api.example.com/v1/photos")
                    data("force_failure", forceFailure)
                    try {
                        val synced = repository.sync(saved, forceFailure)
                        data("upload_url", synced.uploadUrl)
                        data("server_ack", true)
                        steps += WorkflowStepResult(
                            name = "sync_image",
                            startedAt = started,
                            finishedAt = Instant.now(),
                            status = WorkflowStepResult.Status.OK,
                            data = mapOf(
                                "upload_url" to synced.uploadUrl,
                                "server_ack" to true,
                            ),
                            errorMessage = null,
                        )
                    } catch (t: Throwable) {
                        ok = false
                        markFailed(t.message ?: "unknown")
                        steps += WorkflowStepResult(
                            name = "sync_image",
                            startedAt = started,
                            finishedAt = Instant.now(),
                            status = WorkflowStepResult.Status.FAILED,
                            data = mapOf("force_failure" to forceFailure),
                            errorMessage = t.message,
                        )
                        throw t
                    }
                }
            }
        } catch (_: Throwable) {
            // Already captured by workflowTracker.runRoot — fall through to return the partial report.
        }

        return WorkflowReport(workflowName = "photo_workflow", steps = steps, ok = ok)
    }
}
