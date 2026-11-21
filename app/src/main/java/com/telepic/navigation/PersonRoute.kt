package com.telepic.navigation

/**
 * The route for one person's photo grid, pushed from the People tab.
 *
 * Carries only the person id — never a name or a count. Both are live values the detail screen
 * observes, so passing them would give the header a value that goes stale the moment the user
 * renames someone or a rescan moves faces, which is exactly what the album route had to be careful
 * about (see its handling of the album name).
 */
object PersonRoute {
    const val ARG_PERSON_ID = "personId"
    const val PATTERN = "person/{$ARG_PERSON_ID}"

    fun create(personId: Long): String = "person/$personId"
}
