package com.telepic.data.media.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created album: a name and a cover hint. Membership lives in [CustomAlbumMediaEntity].
 *
 * [coverMediaId] is a *hint*, not a derived value — it is the most recently added member, written when
 * media is added. Storing it avoids a correlated subquery on every grid read, and it is nullable
 * because an empty album has no cover: the card shows a placeholder rather than picking something at
 * random to look full.
 *
 * [updatedAt] orders the grid (most recently touched album first), so adding a photo to an album moves
 * it back to the front — the same "recently used" behaviour the rest of the app follows.
 */
@Entity(tableName = "custom_album")
data class CustomAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val coverMediaId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Album membership: which local media belongs to which user-created album.
 *
 * The primary key is `(albumId, localMediaId)`, so adding the same photo to the same album twice is a
 * no-op rather than a duplicate — the honest reading of "it is already in there". Identity is the
 * MediaStore id as a number, matching the rest of the pipeline (never a filename or a path).
 *
 * The foreign key cascades on delete, so removing an album can never strand membership rows that no
 * album can reach. `localMediaId` carries its own index because the reverse lookup ("which albums is
 * this photo in?") is the one that would otherwise scan.
 */
@Entity(
    tableName = "custom_album_media",
    primaryKeys = ["albumId", "localMediaId"],
    indices = [Index(value = ["albumId"]), Index(value = ["localMediaId"])],
    foreignKeys = [
        ForeignKey(
            entity = CustomAlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CustomAlbumMediaEntity(
    val albumId: Long,
    val localMediaId: Long,
    val addedAt: Long,
)

/**
 * One album plus its member count, for the grid. Room maps this by column name.
 *
 * [count] is the stored membership, not a MediaStore count: it is what the user put in the album. A
 * member whose file was deleted outside Telepic stops resolving in the grid but stays in the count,
 * which is the same behaviour Trash already has — membership is a record of intent, not a live query.
 */
data class CustomAlbumRow(
    val id: Long,
    val name: String,
    val coverMediaId: Long?,
    val count: Int,
)
