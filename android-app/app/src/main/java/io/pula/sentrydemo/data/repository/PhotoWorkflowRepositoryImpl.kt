package io.pula.sentrydemo.data.repository

import io.pula.sentrydemo.domain.model.CapturedPhoto
import io.pula.sentrydemo.domain.model.SavedPhoto
import io.pula.sentrydemo.domain.model.SyncedPhoto
import io.pula.sentrydemo.domain.repository.PhotoSyncException
import io.pula.sentrydemo.domain.repository.PhotoWorkflowRepository
import kotlinx.coroutines.delay
import java.time.Instant
import javax.inject.Inject
import kotlin.random.Random

/**
 * Fakes a capture/save/sync pipeline with realistic-feeling delays and a small
 * baseline failure rate. No Sentry calls here — the use case wraps each step.
 */
class PhotoWorkflowRepositoryImpl @Inject constructor() : PhotoWorkflowRepository {

    override suspend fun capture(): CapturedPhoto {
        delay(700)
        return CapturedPhoto(
            filename = "img_${System.currentTimeMillis()}.jpg",
            sizeBytes = 1_200_000L + Random.nextLong(800_000L),
            width = 1920,
            height = 1080,
        )
    }

    override suspend fun save(photo: CapturedPhoto): SavedPhoto {
        delay(350)
        return SavedPhoto(
            source = photo,
            storagePath = "/storage/emulated/0/Android/data/io.pula.sentrydemo/files/${photo.filename}",
        )
    }

    override suspend fun sync(photo: SavedPhoto, forceFailure: Boolean): SyncedPhoto {
        delay(1_100)
        val shouldFail = forceFailure || Random.nextInt(100) < 20
        if (shouldFail) {
            throw PhotoSyncException("Upload failed: HTTP 500 from upstream")
        }
        return SyncedPhoto(
            source = photo,
            uploadUrl = "https://cdn.example.com${photo.storagePath}",
            ackedAt = Instant.now(),
        )
    }
}
