package io.pula.sentrydemo.domain.repository

import io.pula.sentrydemo.domain.model.CapturedPhoto
import io.pula.sentrydemo.domain.model.SavedPhoto
import io.pula.sentrydemo.domain.model.SyncedPhoto

/**
 * Side-effect-free contract for the demo capture/save/sync pipeline. The
 * concrete implementation simulates each operation with a delay; observability
 * (Sentry spans, breadcrumbs, error attribution) is layered on top in the use
 * case so the domain stays platform-agnostic.
 */
interface PhotoWorkflowRepository {
    suspend fun capture(): CapturedPhoto
    suspend fun save(photo: CapturedPhoto): SavedPhoto
    /** @param forceFailure when true, deterministically throws [PhotoSyncException]. */
    suspend fun sync(photo: SavedPhoto, forceFailure: Boolean): SyncedPhoto
}

class PhotoSyncException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
