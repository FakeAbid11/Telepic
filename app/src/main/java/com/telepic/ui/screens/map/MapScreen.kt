package com.telepic.ui.screens.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.telepic.R
import com.telepic.data.media.MapCameraPlan
import com.telepic.data.media.MapCameraResolver
import com.telepic.data.media.MapClusterer
import com.telepic.data.media.MapZoomPrecision
import com.telepic.data.media.SavedCamera
import com.telepic.domain.media.GeoLocation
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.navigation.MapFocus
import com.telepic.navigation.MapFocusRequest
import com.telepic.permissions.MediaPermissionState
import com.telepic.permissions.rememberForcedMediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.screens.viewer.formatCoordinate
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
 * single pin centres the map on it and opens a preview sheet offering the full Viewer. Tiles come
 * from a public OSM provider — coordinates and EXIF are NEVER uploaded, and no location is shown for
 * media that lacks GPS.
 *
 * The osmdroid surface is device-bound: pan/zoom, tile loading and marker rendering are validated on
 * a real device and are NOT exercised by Robolectric. The map *logic* (GPS parsing, clustering,
 * zoom→precision, selection→identity, camera policy) lives behind pure, unit-tested seams.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenMedia: (Long) -> Unit,
    focusRequest: MapFocusRequest,
    modifier: Modifier = Modifier,
    permissionStateOverride: MediaPermissionState? = null,
    onOpenSettings: () -> Unit = {},
) {
    // One controller per composition: the real (launcher-owning) one unless the caller forces a state.
    val controller = if (permissionStateOverride != null) {
        rememberForcedMediaPermissionState(permissionStateOverride)
    } else {
        rememberMediaPermissionState()
    }
    val permissionState = controller.state
    val locations by viewModel.locations.collectAsStateWithLifecycle()
    val locationCount by viewModel.locationCount.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanFailed by viewModel.scanFailed.collectAsStateWithLifecycle()
    val savedCamera by viewModel.savedCamera.collectAsStateWithLifecycle()
    val cameraResolved by viewModel.cameraResolved.collectAsStateWithLifecycle()
    val focus by focusRequest.pending.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing

    LaunchedEffect(permissionState.hasAccess) { if (permissionState.hasAccess) viewModel.refresh() }

    Surface(modifier = modifier.fillMaxSize(), color = TelepicTokens.colors.mediaBackdrop) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The honest "N locations" affordance the ViewModel has always exposed — now consumed.
            // The gear follows it so Settings is the right-most control here too, matching every
            // other top-level screen.
            ScreenHeader(
                title = stringResource(R.string.map_title),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (locationCount > 0) {
                            Text(
                                text = stringResource(R.string.map_locations_count, locationCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsAction(onClick = onOpenSettings)
                    }
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
                // does not flash before the first markers land. The remembered camera is read before
                // the surface exists, for the same reason: the map must open already framed.
                !cameraResolved || (isScanning && locations.isEmpty()) ->
                    LoadingState(modifier = Modifier.weight(1f))
                // A failed scan used to fall into the branch below and read as "no locations yet" —
                // telling the user their photos carry no GPS data when the scan had thrown. Gated on
                // `locations.isEmpty()` so an already-known set stays on screen instead.
                scanFailed && locations.isEmpty() -> ErrorState(
                    title = stringResource(R.string.map_scan_failed_title),
                    explanation = stringResource(R.string.map_scan_failed_description),
                    onRetry = viewModel::refresh,
                    retryLabel = stringResource(R.string.common_retry),
                    modifier = Modifier.weight(1f),
                )
                locations.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LocationOff,
                    title = stringResource(R.string.map_empty_title),
                    description = stringResource(R.string.map_empty_description),
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    OsmMap(
                        locations = locations,
                        onOpenMedia = onOpenMedia,
                        savedCamera = savedCamera,
                        focus = focus,
                        onFocusConsumed = focusRequest::consume,
                        onCameraChanged = viewModel::rememberCamera,
                        modifier = Modifier.weight(1f),
                    )
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
    savedCamera: SavedCamera?,
    focus: MapFocus?,
    onFocusConsumed: () -> Unit,
    onCameraChanged: (SavedCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Zoom-adaptive grouping: the osmdroid zoom listener only updates this band; clusters recompute
    // when the band changes, not on every pixel of pinch — cheap for the pure clusterer either way.
    var precision by remember { mutableIntStateOf(MapZoomPrecision.DEFAULT) }
    val pins = remember(locations, precision) { MapClusterer.cluster(locations, precision) }
    // Fit the camera to the actual photo coordinates once per distinct data set — opening the map
    // frames the user's photos instead of a fixed world view.
    val plan = remember(pins) {
        MapCameraPlan.forPoints(pins.map { it.latitude to it.longitude })
    }
    // The framing applied when the surface is created, captured once. Priority: an explicit focus
    // (another screen asked for one photo) → the remembered position → all the user's photos.
    val initialCamera = remember {
        MapCameraResolver.resolve(
            focus = focus?.let { GeoLocation(it.latitude, it.longitude) },
            saved = savedCamera,
            plan = plan,
        )
    }
    var initialApplied by remember { mutableStateOf(false) }
    // An imperative camera move: the recenter button, or a tapped pin. `update` applies it once and
    // clears it, so a plain recomposition can never re-animate the map and fight the user's panning.
    var pendingCamera by remember { mutableStateOf<MapCameraResolver.Decision?>(null) }
    var selected by remember { mutableStateOf<MapClusterer.MapPin.Single?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    // The badge draws its count onto a `primary` fill, so the count must use `onPrimary`: in the
    // dark scheme `primary` is a light blue, and white on it measured 2.33:1 (unreadable).
    val clusterFill = MaterialTheme.colorScheme.primary.toArgb()
    val clusterCountColor = MaterialTheme.colorScheme.onPrimary.toArgb()

    // Persist where the map was left. Written on pause *and* on teardown: leaving the map for another
    // tab disposes it without a pause, and that final position is the one worth remembering.
    val captureCamera: () -> Unit = {
        mapViewRef?.let { view ->
            view.mapCenter?.let { center ->
                onCameraChanged(SavedCamera(center.latitude, center.longitude, view.zoomLevelDouble))
            }
        }
    }

    // osmdroid tiles/network only make sense while the surface is foregrounded; without this the
    // MapView keeps fetching tiles with the app backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    captureCamera()
                    mapViewRef?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            captureCamera()
            mapViewRef?.onPause()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val prefs = context.getSharedPreferences("telepic_osmdroid", android.content.Context.MODE_PRIVATE)
                val config = Configuration.getInstance()
                config.load(context, prefs)
                if (config.userAgentValue.isNullOrBlank()) config.userAgentValue = context.packageName
                // The focus is claimed by the surface that consumes it, so it is never replayed onto a
                // later visit that the user did not ask for.
                if (focus != null) onFocusConsumed()
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
                            // TalkBack parity with the photo grids: the label rides on the marker's
                            // title (osmdroid has no per-marker contentDescription), and the map view
                            // itself announces how many pins are on screen. The coordinates make each
                            // pin's label distinct — one shared "at this location" for every pin told
                            // the user nothing about which photo it belonged to. osmdroid markers are
                            // canvas overlays, so this fixes label identity, not per-pin navigation.
                            marker.title = mapView.context.getString(
                                R.string.map_marker_description,
                                formatCoordinate(pin.latitude, pin.longitude),
                            )
                            marker.setOnMarkerClickListener { _, _ ->
                                // Centre and offer a preview rather than throwing the user straight
                                // into the Viewer — the map keeps its context behind the sheet.
                                pendingCamera = MapCameraResolver.focus(pin.latitude, pin.longitude)
                                selected = pin
                                true
                            }
                        }
                        is MapClusterer.MapPin.Cluster -> {
                            // A count badge (drawn, never an icon pack) so clusters read differently from
                            // singles at any zoom band.
                            marker.setIcon(
                                MapPinBadge.clusterDrawable(
                                    mapView.context,
                                    pin.size,
                                    clusterFill,
                                    clusterCountColor,
                                ),
                            )
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            marker.title =
                                mapView.context.getString(R.string.map_cluster_description, pin.size)
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
                // TalkBack reads the map as one focusable surface: announce how many pins it holds.
                mapView.contentDescription =
                    mapView.context.getString(R.string.map_view_description, pins.size)
                // The opening framing is applied exactly once — later recompositions (a new overlay, a
                // changed badge colour) must never move the camera out from under the user.
                if (!initialApplied) {
                    (initialCamera as? MapCameraResolver.Decision.Apply)?.let {
                        mapView.controller.setCenter(GeoPoint(it.centerLat, it.centerLon))
                        mapView.controller.setZoom(it.zoom)
                    }
                    initialApplied = true
                }
                pendingCamera?.let { decision ->
                    if (decision is MapCameraResolver.Decision.Apply) {
                        mapView.controller.setCenter(GeoPoint(decision.centerLat, decision.centerLon))
                        mapView.controller.setZoom(decision.zoom)
                    }
                    pendingCamera = null
                }
                mapView.invalidate()
            },
            onRelease = { mapView ->
                mapView.onDetach()
                mapViewRef = null
            },
        )
        // Reframes all the photos on demand, so a stray pan is never a dead end. The regular FAB
        // (56dp) rather than the small one (40dp): Material3's FAB does not expand its own touch
        // target — `FloatingActionButtonKt` never applies `minimumInteractiveComponentSize`, and
        // neither does the clickable `Surface` it delegates to — so the small variant is a 40dp
        // target, under the 48dp accessibility floor, on this screen's only way out of a stray pan.
        FloatingActionButton(
            onClick = { pendingCamera = MapCameraResolver.recenter(plan) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(TelepicTokens.spacing.lg),
        ) {
            Icon(
                imageVector = Icons.Outlined.CenterFocusStrong,
                contentDescription = stringResource(R.string.map_recenter),
            )
        }
    }

    val preview = selected
    if (preview != null) {
        // The pin carries only identity + position; the thumbnail URI lives on the source item, so it
        // is looked up by the same MediaStore id rather than duplicated onto every pin.
        val contentUri = remember(preview, locations) {
            locations.firstOrNull { it.mediaId == preview.mediaId }?.contentUri
        }
        MapPinPreviewSheet(
            mediaId = preview.mediaId,
            latitude = preview.latitude,
            longitude = preview.longitude,
            contentUri = contentUri,
            onDismiss = { selected = null },
            onOpen = { mediaId ->
                selected = null
                onOpenMedia(mediaId)
            },
        )
    }
}

/**
 * A tapped pin's preview: what is here, and a way into the full Viewer. Deliberately shows only what
 * the map actually knows (the image itself and its coordinates) — no date or place is fabricated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapPinPreviewSheet(
    mediaId: Long,
    latitude: Double,
    longitude: Double,
    contentUri: String?,
    onDismiss: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = TelepicTokens.spacing

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenMargin)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            if (contentUri != null) {
                AsyncImage(
                    model = contentUri,
                    contentDescription = stringResource(R.string.map_preview_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            Text(
                text = formatCoordinate(latitude, longitude),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { onOpen(mediaId) }) {
                Text(stringResource(R.string.map_selected_open))
            }
        }
    }
}

private const val MAX_ZOOM = 19.0
