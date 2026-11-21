package com.telepic.data.face

import com.telepic.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The grouping strictness the user has chosen: read by the workers and the tab, written by the
 * grouping dialog.
 *
 * One seam rather than a lambda plus a setter, because both halves have to agree about the same
 * preference — a screen that could change the value a worker never re-reads would be a setting that
 * does nothing.
 */
interface FaceGroupingSettings {

    /** The stored preset, defaulting when nothing has been chosen. */
    val preset: Flow<FaceSimilarityPreset>

    /** Persists [preset] for the next rebuild and the next scan. */
    suspend fun setPreset(preset: FaceSimilarityPreset)

    /** The stored preset, read once. For callers that only need the value (a worker run). */
    suspend fun current(): FaceSimilarityPreset = preset.first()
}

/**
 * [FaceGroupingSettings] over the app's preferences.
 *
 * The preference is stored as a *name* and resolved through [FaceSimilarityPreset.fromName], so an
 * unrecognised or stale value degrades to the default instead of throwing, and so the stored number
 * never silently means something else after the embedder is recalibrated.
 */
class PreferencesFaceGroupingSettings(
    private val settingsRepository: SettingsRepository,
) : FaceGroupingSettings {

    override val preset: Flow<FaceSimilarityPreset> =
        settingsRepository.faceSimilarityPreset.map(FaceSimilarityPreset::fromName)

    override suspend fun setPreset(preset: FaceSimilarityPreset) {
        settingsRepository.setFaceSimilarityPreset(preset.name)
    }
}
