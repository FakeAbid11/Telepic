package com.telepic.ui.screens.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.R
import com.telepic.data.media.MapCameraPlan
import com.telepic.data.media.MapClusterer
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The interactive OpenStreetMap photo map (Phase 10). Renders geotagged local media as markers
 * (grouped by the pure [MapClusterer]) via osmdroid; tapping a marker opens the shared Viewer by the
 * item's own MediaStore id. Tiles come from a public OSM provider — coordinates and EXIF are NEVER
 * uploaded, and no location is shown for media that lacks GPS.
 *
 * The osmdroid surface is device-bound: pan/zoom, tile loading and marker rendering are validated on
 * a real device and are NOT exercised by Robolectric. The map *logic* (GPS parsing, clustering,
 * selection→identity) lives behind pure, unit-tested seams.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
    permissionStateOverride: MediaPermissionState? = null,
) {
    val controller = rememberMediaPermissionState()
    val permissionState = permissionStateOverride ?: controller.state
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.map_title))
            when {
                !permissionState.hasAccess -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.map_permission_title),
                    description = stringResource(R.string.map_permission_description),
                    primaryAction = StateAction(
                        label = stringResource(
                            if (permissionState == MediaPermissionState.PermanentlyDenied)
                                R.string.photos_open_settings else R.string.photos_allow_access,
                        ),
                        onClick = if (permissionState == MediaPermissionState.PermanentlyDenied)
                            controller.openAppSettings else controller.requestPermission,
                    ),
                )
                // Distinguish an in-progress scan from a genuinely empty result so the empty state
                // does not flash before the first markers land.
                isScanning && pins.isEmpty() -> LoadingState(modifier = Modifier.weight(1f))
                pins.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LocationOff,
                    title = stringResource(R.string.map_empty_title),
                    description = stringResource(R.string.map_empty_description),
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    OsmMap(pins = pins, onOpenMedia = onOpenMedia, modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.map_attribution),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun OsmMap(pins: List<MapClusterer.MapPin>, onOpenMedia: (Long) -> Unit, modifier: Modifier = Modifier) {
    // Fit the camera to the actual photo coordinates once per distinct data set — opening the map
    // frames the user's photos instead of a fixed world view. A stable key prevents the repeated
    // camera animation the old per-recomposition rebuild caused; an empty/plan-less set keeps the
    // neutral factory view (never a fabricated center).
    val plan = remember(pins) {
        MapCameraPlan.forPoints(pins.map { it.latitude to it.longitude })
    }
    var fittedKey by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val prefs = context.getSharedPreferences("telepic_osmdroid", android.content.Context.MODE_PRIVATE)
            val config = Configuration.getInstance()
            config.load(context, prefs)
            if (config.userAgentValue.isNullOrBlank()) config.userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(2.0)
                setExpectedCenter(GeoPoint(20.0, 0.0))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            pins.forEach { pin ->
                val marker = Marker(mapView)
                marker.setPosition(GeoPoint(pin.latitude, pin.longitude))
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.setOnMarkerClickListener { _, _ ->
                    when (pin) {
                        is MapClusterer.MapPin.Single -> onOpenMedia(pin.mediaId)
                        is MapClusterer.MapPin.Cluster -> {
                            // Center on the cluster, then zoom in — instead of zooming at the current view.
                            mapView.controller.setCenter(GeoPoint(pin.latitude, pin.longitude))
                            mapView.controller.setZoom((mapView.zoomLevelDouble + 2.0).coerceAtMost(MAX_ZOOM))
                        }
                    }
                    true
                }
                mapView.overlays.add(marker)
            }
            // Apply the fit only when the coordinate set actually changes (not on every recomposition).
            plan?.let {
                val key = "${it.centerLat},${it.centerLon},${it.zoom}"
                if (key != fittedKey) {
                    mapView.controller.setCenter(GeoPoint(it.centerLat, it.centerLon))
                    mapView.controller.setZoom(it.zoom)
                    fittedKey = key
                }
            }
            mapView.invalidate()
        },
        onRelease = { mapView -> mapView.onDetach() },
    )
}

private const val MAX_ZOOM = 19.0
