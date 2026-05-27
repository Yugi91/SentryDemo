package io.pula.sentrydemo.domain.model

import java.time.Instant

data class WorkflowStepResult(
    val name: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val status: Status,
    val data: Map<String, Any?>,
    val errorMessage: String?,
) {
    enum class Status { OK, FAILED }

    val durationMs: Long get() = finishedAt.toEpochMilli() - startedAt.toEpochMilli()
}

data class WorkflowReport(
    val workflowName: String,
    val steps: List<WorkflowStepResult>,
    val ok: Boolean,
)

data class CapturedPhoto(
    val filename: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

data class SavedPhoto(
    val source: CapturedPhoto,
    val storagePath: String,
)

data class SyncedPhoto(
    val source: SavedPhoto,
    val uploadUrl: String,
    val ackedAt: Instant,
)
