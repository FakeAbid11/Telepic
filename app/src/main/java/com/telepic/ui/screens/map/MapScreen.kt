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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telepic.R
import com.telepic.data.media.MapCameraPlan
import com.telepic.data.media.MapClusterer
import com.telepic.data.media.MapZoomPrecision
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.StateAction
import com.telepic.ui.theme.TelepicTokens
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The interactive OpenStreetMap photo map (Phase 10). Renders geotagged local media as markers via
 * osmdroid, grouped by the pure [MapClusterer] at a grid precision that follows the current zoom
 * ([MapZoomPrecision]) — zoom in, clusters split. Clusters render a drawn count badge; tapping a
 * single pin opens the shared Viewer by the item's own MediaStore id, tapping a cluster centers and
 * zooms in. Tiles come from a public OSM provider — coordinates and EXIF are NEVER uploaded, and no
 * location is shown for media that lacks GPS.
 *
 * The osmdroid surface is device-bound: pan/zoom, tile loading and marker rendering are validated on
 * a real device and are NOT exercised by Robolectric. The map *logic* (GPS parsing, clustering,
 * zoom→precision, selection→identity) lives behind pure, unit-tested seams.
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
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val locationCount by viewModel.locationCount.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The honest "N locations" affordance the ViewModel has always exposed — now consumed.
            ScreenHeader(
                title = stringResource(R.string.map_title),
                trailing = if (locationCount > 0) {
                    {
                        Text(
                            text = stringResource(R.string.map_locations_count, locationCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    null
                },
            )
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
                isScanning && locations.isEmpty() -> LoadingState(modifier = Modifier.weight(1f))
                locations.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LocationOff,
                    title = stringResource(R.string.map_empty_title),
                    description = stringResource(R.string.map_empty_description),
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    OsmMap(locations = locations, onOpenMedia = onOpenMedia, modifier = Modifier.weight(1f))
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
private fun OsmMap(
    locations: List<GeotaggedMedia>,
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Zoom-adaptive grouping: the osmdroid zoom listener only updates this band; clusters recompute
    // when the band changes, not on every pixel of pinch — cheap for the pure clusterer either way.
    var precision by remember { mutableIntStateOf(MapZoomPrecision.DEFAULT) }
    val pins = remember(locations, precision) { MapClusterer.cluster(locations, precision) }
    // Fit the camera to the actual photo coordinates once per distinct data set — opening the map
    // frames the user's photos instead of a fixed world view. A stable key prevents the repeated
    // camera animation the old per-recomposition rebuild caused; an empty/plan-less set keeps the
    // neutral factory view (never a fabricated center).
    val plan = remember(pins) {
        MapCameraPlan.forPoints(pins.map { it.latitude to it.longitude })
    }
    var fittedKey by remember { mutableStateOf<String?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val clusterFill = MaterialTheme.colorScheme.primary.toArgb()

    // osmdroid tiles/network only make sense while the surface is foregrounded; without this the
    // MapView keeps fetching tiles with the app backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onPause()
        }
    }

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
                addMapListener(object : MapListener {
                    // osmdroid 6.1's listener is callback-per-event-type; only zoom changes the grid.
                    override fun onScroll(event: ScrollEvent): Boolean = false
                    override fun onZoom(event: ZoomEvent): Boolean {
                        val next = MapZoomPrecision.forZoom(event.zoomLevel)
                        if (next != precision) precision = next
                        return false
                    }
                })
                mapViewRef = this
            }
        },
        update = { mapView ->
            mapView.overlays.clear()
            pins.forEach { pin ->
                val marker = Marker(mapView)
                marker.setPosition(GeoPoint(pin.latitude, pin.longitude))
                when (pin) {
                    is MapClusterer.MapPin.Single -> {
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        marker.setOnMarkerClickListener { _, _ ->
                            onOpenMedia(pin.mediaId)
                            true
                        }
                    }
                    is MapClusterer.MapPin.Cluster -> {
                        // A count badge (drawn, never an icon pack) so clusters read differently from
                        // singles at any zoom band.
                        marker.setIcon(
                            MapPinBadge.clusterDrawable(mapView.context, pin.size, clusterFill),
                        )
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        marker.setOnMarkerClickListener { _, _ ->
                            // Center on the cluster, then zoom in — the recluster follows via the
                            // zoom listener; no zoom level is invented here.
                            mapView.controller.setCenter(GeoPoint(pin.latitude, pin.longitude))
                            mapView.controller.setZoom((mapView.zoomLevelDouble + 2.0).coerceAtMost(MAX_ZOOM))
                            true
                        }
                    }
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
        onRelease = { mapView ->
            mapView.onDetach()
            mapViewRef = null
        },
    )
}

private const val MAX_ZOOM = 19.0
