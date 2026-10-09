package com.telepix.navigation

/** The three contextual organization collections. Kept out of the bottom bar (reached from Albums). */
enum class OrganizationKind { FAVORITES, ARCHIVE, TRASH }

object OrganizationRoute {
    const val ARG_KIND = "kind"
    const val PATTERN = "organization/{$ARG_KIND}"

    fun create(kind: OrganizationKind): String = "organization/${kind.name.lowercase()}"

    fun kindOf(raw: String?): OrganizationKind? = when (raw?.lowercase()) {
        "favorites" -> OrganizationKind.FAVORITES
        "archive" -> OrganizationKind.ARCHIVE
        "trash" -> OrganizationKind.TRASH
        else -> null
    }
}
