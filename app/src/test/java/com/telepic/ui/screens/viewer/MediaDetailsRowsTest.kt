package com.telepic.ui.screens.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.telepic.R
import com.telepic.domain.backup.MediaBackupVisualState
import com.telepic.domain.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Details sheet actually lists, for both a local and a cloud item.
 *
 * The two behaviours worth pinning are omissions rather than values. A file with no EXIF must show no
 * exposure rows at all — not four lines reading "Unknown" — and an item the backup queue has no row
 * for must show no Backup row at all, because after a reinstall that is equally true of items the
 * cloud still holds and "Not backed up" would be a false claim about the user's data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaDetailsRowsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun details(
        mediaType: MediaType? = MediaType.PHOTO,
        durationMillis: Long? = null,
        bucketName: String? = null,
        relativePath: String? = null,
        backupState: MediaBackupVisualState? = null,
        isCloud: Boolean = false,
        cloudMessageId: Long? = null,
        cloudOriginalDownloaded: Boolean = false,
        iso: Int? = null,
        fNumber: Double? = null,
        exposureTimeSeconds: Double? = null,
        focalLengthMm: Double? = null,
    ) = MediaDetails(
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 2048,
        width = 100,
        height = 50,
        captureMillis = null,
        libraryDateMillis = 0L,
        libraryAddedMillis = null,
        cameraMake = null,
        cameraModel = null,
        latitude = null,
        longitude = null,
        mediaType = mediaType,
        durationMillis = durationMillis,
        bucketName = bucketName,
        relativePath = relativePath,
        backupState = backupState,
        isCloud = isCloud,
        cloudMessageId = cloudMessageId,
        cloudOriginalDownloaded = cloudOriginalDownloaded,
        iso = iso,
        fNumber = fNumber,
        exposureTimeSeconds = exposureTimeSeconds,
        focalLengthMm = focalLengthMm,
    )

    private fun labels(details: MediaDetails): List<Int> = details.displayRows(context).map { it.labelRes }

    private fun valueOf(details: MediaDetails, labelRes: Int): String =
        details.displayRows(context).first { it.labelRes == labelRes }.value

    @Test
    fun `a plain photo shows identity and source but no exposure rows`() {
        val rows = labels(details())

        assertTrue(rows.contains(R.string.details_file_name))
        assertTrue(rows.contains(R.string.details_type))
        assertTrue(rows.contains(R.string.details_source))
        // No EXIF → no exposure rows at all, rather than four rows reading "Unknown".
        assertFalse(rows.contains(R.string.details_iso))
        assertFalse(rows.contains(R.string.details_aperture))
        assertFalse(rows.contains(R.string.details_exposure))
        assertFalse(rows.contains(R.string.details_focal_length))
        // A photo has no duration.
        assertFalse(rows.contains(R.string.details_duration))
    }

    @Test
    fun `the exposure set renders when the file carries it`() {
        val details = details(iso = 100, fNumber = 2.8, exposureTimeSeconds = 0.004, focalLengthMm = 4.3)

        assertEquals("100", valueOf(details, R.string.details_iso))
        assertEquals("f/2.8", valueOf(details, R.string.details_aperture))
        assertEquals("1/250 s", valueOf(details, R.string.details_exposure))
        assertEquals("4.3 mm", valueOf(details, R.string.details_focal_length))
    }

    @Test
    fun `a video shows its duration`() {
        val details = details(mediaType = MediaType.VIDEO, durationMillis = 90_000L)

        assertEquals("1:30", valueOf(details, R.string.details_duration))
    }

    @Test
    fun `folder and path appear only when the item has them`() {
        assertFalse(labels(details()).contains(R.string.details_folder))
        assertFalse(labels(details()).contains(R.string.details_path))

        val details = details(bucketName = "Camera", relativePath = "DCIM/Camera/")
        assertEquals("Camera", valueOf(details, R.string.details_folder))
        assertEquals("DCIM/Camera/", valueOf(details, R.string.details_path))
    }

    @Test
    fun `an absent backup row is omitted rather than reported as not backed up`() {
        // Null means the queue holds no row for this item. That is also true of everything the cloud
        // holds after a reinstall, so the row must not exist at all in that case.
        assertFalse(labels(details()).contains(R.string.details_backup))
    }

    @Test
    fun `a known backup state reuses the grid badge wording`() {
        val details = details(backupState = MediaBackupVisualState.BACKED_UP)

        assertEquals(
            context.getString(R.string.backup_state_backed_up),
            valueOf(details, R.string.details_backup),
        )
    }

    @Test
    fun `a cloud item reports its remote identity and no local path`() {
        val details = details(isCloud = true, cloudMessageId = 4242L, cloudOriginalDownloaded = false)

        assertEquals(context.getString(R.string.viewer_source_cloud), valueOf(details, R.string.details_source))
        assertEquals("4242", valueOf(details, R.string.details_telegram_message))
        assertEquals(
            context.getString(R.string.details_cloud_original_remote),
            valueOf(details, R.string.details_cloud_original),
        )
        // A cloud item has no local file, so the local-only rows must not be present.
        val rows = labels(details)
        assertFalse(rows.contains(R.string.details_folder))
        assertFalse(rows.contains(R.string.details_path))
        assertFalse(rows.contains(R.string.details_camera))
    }

    @Test
    fun `a downloaded cloud original says so`() {
        val details = details(isCloud = true, cloudMessageId = 1L, cloudOriginalDownloaded = true)

        assertEquals(
            context.getString(R.string.details_cloud_original_downloaded),
            valueOf(details, R.string.details_cloud_original),
        )
    }

    @Test
    fun `a local item reports itself as local`() {
        assertEquals(
            context.getString(R.string.viewer_source_local),
            valueOf(details(), R.string.details_source),
        )
    }
}
