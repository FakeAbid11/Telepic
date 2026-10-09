package com.telepix.ui.screens.map

import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepix.R
import com.telepix.data.media.MapClusterer
import com.telepix.permissions.MediaPermissionState
import com.telepix.permissions.rememberMediaPermissionState
import com.telepix.ui.components.EmptyState
import com.telepix.ui.components.ScreenHeader
import com.telepix.ui.theme.TelepixTokens
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
    val spacing = TelepixTokens.spacing

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepixTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = stringResource(R.string.map_title))
            when {
                !permissionState.hasAccess -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.map_permission_title),
                    description = stringResource(R.string.map_permission_description),
                )
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
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val prefs = context.getSharedPreferences("telepix_osmdroid", android.content.Context.MODE_PRIVATE)
            val config = Configuration.getInstance()
            config.load(context, prefs)
            if (config.userAgentValue.isNullOrBlank()) config.userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(android.view.View.GONE)
                controller.setZoom(2.0)
                setExpectedCenter(GeoPoint(20.0, 0.0))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            pins.forEach { pin ->
                val marker = Marker(mapView)
                marker.position = GeoPoint(pin.latitude, pin.longitude)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.infoWindow = null
                if (pin is MapClusterer.MapPin.Single) {
                    val id = pin.mediaId
                    marker.onMarkerClickListener = Marker.OnMarkerClickListener { _, _ -> onOpenMedia(id); true }
                    marker.title = null
                } else {
                    marker.onMarkerClickListener = Marker.OnMarkerClickListener { _, _ ->
                        mapView.controller.zoomTo(pin.latitude, pin.longitude, 12.0, 300L)
                        true
                    }
                }
                mapView.overlays.add(marker)
            }
            mapView.invalidate()
        },
        onRelease = { mapView -> mapView.onDetach() },
    )
}
