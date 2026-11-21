package com.telepic.ui.screens.people

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.telepic.R
import com.telepic.domain.face.Person

/**
 * The label a person's card and screen title show.
 *
 * In the UI layer rather than on [Person] because the unnamed fallback is a *translated* string: a
 * hard-coded `"Person $id"` in the domain model would ship English to every locale, and the model has
 * no business depending on Android resources.
 *
 * The fallback is deliberately numbered rather than "Unknown": a grid of identical labels is useless
 * for finding someone, and the id gives the user something to refer to when merging or renaming.
 */
@Composable
internal fun personName(person: Person): String =
    person.name ?: stringResource(R.string.people_unnamed, person.personId)
