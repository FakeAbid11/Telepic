package com.telepic.data.backup

import android.net.Uri
import com.telepic.data.backup.BackupMapping.toDomain
import com.telepic.data.backup.BackupMapping.toQueueEntity
import com.telepic.domain.backup.BackupState
import com.telepic.domain.media.LocalMedia
import com.telepic.domain.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the queue-row → domain mapping's fallback for an unrecognised `state` string.
 *
 * The fallback itself is not new; what this guards is that the *logging* added to it did not change
 * the default (a corrupted row still resolves to [BackupState.NOT_BACKED_UP] rather than throwing).
 * Robolectric is required because the fallback logs through [com.telepic.diagnostics.TelepicLog].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupMappingTest {

    private val media = LocalMedia(
        id = 1L,
        contentUri = Uri.parse("content://media/external/images/1"),
        type = MediaType.PHOTO,
        mimeType = "image/jpeg",
        displayName = "p1.jpg",
        dateMillis = 1_700_000_000_000L,
        durationMillis = null,
        width = 100,
        height = 100,
        sizeBytes = 1000L,
        bucketId = 1L,
        bucketName = "Camera",
        relativePath = "DCIM/",
    )

    private fun rowWithState(state: String) =
        media.toQueueEntity(nowMillis = 1L).copy(state = state)

    @Test
    fun `an unknown state is logged and falls back to NOT_BACKED_UP`() {
        assertEquals(BackupState.NOT_BACKED_UP, rowWithState("NOT_A_STATE").toDomain().state)
    }

    @Test
    fun `a known state is preserved`() {
        assertEquals(BackupState.BACKED_UP, rowWithState(BackupState.BACKED_UP.name).toDomain().state)
    }
}
