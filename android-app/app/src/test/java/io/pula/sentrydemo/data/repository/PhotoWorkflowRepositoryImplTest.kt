package io.pula.sentrydemo.data.repository

import com.google.common.truth.Truth.assertThat
import io.pula.sentrydemo.domain.repository.PhotoSyncException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotoWorkflowRepositoryImplTest {

    private val repo = PhotoWorkflowRepositoryImpl()

    @Test
    fun `capture returns a photo with non-empty filename and positive size`() = runTest {
        val photo = repo.capture()

        assertThat(photo.filename).startsWith("img_")
        assertThat(photo.filename).endsWith(".jpg")
        assertThat(photo.sizeBytes).isGreaterThan(0L)
        assertThat(photo.width).isEqualTo(1920)
        assertThat(photo.height).isEqualTo(1080)
    }

    @Test
    fun `save returns a SavedPhoto pointing at the package's external storage path`() = runTest {
        val photo = repo.capture()
        val saved = repo.save(photo)

        assertThat(saved.source).isEqualTo(photo)
        assertThat(saved.storagePath).contains("io.pula.sentrydemo")
        assertThat(saved.storagePath).endsWith(photo.filename)
    }

    @Test
    fun `sync with forceFailure=true always throws PhotoSyncException`() = runTest {
        val photo = repo.capture()
        val saved = repo.save(photo)

        var caught: Throwable? = null
        try {
            repo.sync(saved, forceFailure = true)
        } catch (t: Throwable) {
            caught = t
        }

        assertThat(caught).isInstanceOf(PhotoSyncException::class.java)
        assertThat(caught).hasMessageThat().contains("HTTP 500")
    }

    @Test
    fun `sync with forceFailure=false eventually succeeds and returns a CDN url`() = runTest {
        val photo = repo.capture()
        val saved = repo.save(photo)

        // Repository has a ~20% baseline random failure; retry until we hit success.
        var synced: io.pula.sentrydemo.domain.model.SyncedPhoto? = null
        var attempts = 0
        while (synced == null && attempts < 25) {
            try {
                synced = repo.sync(saved, forceFailure = false)
            } catch (_: PhotoSyncException) {
                /* retry */
            }
            attempts++
        }

        assertThat(synced).isNotNull()
        assertThat(synced!!.uploadUrl).startsWith("https://cdn.example.com")
        assertThat(synced.uploadUrl).endsWith(photo.filename)
    }
}
