package com.telepic.data.media

import com.telepic.domain.media.LocalMedia

/**
 * Loads one page of local media, newest-first.
 *
 * Abstracted from the ContentResolver so the paging source, repository, and tests can supply a
 * fake. The real implementation never touches the main thread.
 */
interface MediaPageLoader {
    /**
     * @param offset number of newest items to skip.
     * @param limit maximum items to return.
     * @return at most [limit] items ordered newest-first; may be empty at the end of the library.
     */
    suspend fun load(offset: Int, limit: Int): List<LocalMedia>
}
