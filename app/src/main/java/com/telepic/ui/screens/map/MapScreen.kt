package com.telepic.ui.screens.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.telepic.map.TelepicTileSources
import com.telepic.navigation.MapFocus
import com.telepic.navigation.MapFocusRequest
import com.telepic.permissions.MediaPermissionState
import java.util.Locale
import com.telepic.permissions.rememberForcedMediaPermissionState
import com.telepic.permissions.rememberMediaPermissionState
import com.telepic.ui.components.EmptyState
import com.telepic.ui.components.ErrorState
import com.telepic.ui.components.LoadingState
import com.telepic.ui.components.PartialAccessBanner
import com.telepic.ui.components.ScreenHeader
import com.telepic.ui.components.SettingsAction
import com.telepic.ui.components.StateAction
import com.telepic.ui.screens.viewer.formatCoordinate
import com.telepic.ui.theme.LocalDarkTheme
import com.telepic.ui.theme.TelepicTokens
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The interactive OpenStreetMap photo map (Phase 10). Renders geotagged local media as markers via
 * osmdroid, grouped by the pure [MapClusterer] at a grid precision that follows the current zoom
 * ([MapZoomPrecision]) — zoom in, clusters split. Clusters render a drawn count badge; tapping a
 * single pin centres the map on it and opens a preview sheet offering the full Viewer. Tiles come
 * from CARTO's keyed basemaps over OpenStreetMap data — coordinates and EXIF are NEVER uploaded, and
 * no location is shown for media that lacks GPS.
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
    // A list is the accessible equivalent of the canvas markers: TalkBack can reach every item.
    var showLocationList by remember { mutableStateOf(false) }
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val scanFailed by viewModel.scanFailed.collectAsStateWithLifecycle()
    val savedCamera by viewModel.savedCamera.collectAsStateWithLifecycle()
    val cameraResolved by viewModel.cameraResolved.collectAsStateWithLifecycle()
    val scanSettled by viewModel.scanSettled.collectAsStateWithLifecycle()
    val focus by focusRequest.pending.collectAsStateWithLifecycle()
    val spacing = TelepicTokens.spacing
    // The attribution is a link, so it needs a way to open one.
    val uriHandler = LocalUriHandler.current
    // The app's own theme decision, not the device's: the basemap has to agree with the rest of the
    // UI even when the user forced Dark on a Light device (or the reverse).
    val darkTiles = LocalDarkTheme.current

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
                            TextButton(onClick = { showLocationList = true }) {
                                Text(stringResource(R.string.map_list_action))
                            }
                            Text(
                                text = pluralStringResource(R.plurals.map_locations_count, locationCount, locationCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SettingsAction(onClick = onOpenSettings)
                    }
                },
            )
            // Partial access honesty: the Photos timeline says so, and this screen did not — so the
            // same library looked complete on one tab and partial on another, and the count beside
            // the title was read as the whole library.
            if (permissionState == MediaPermissionState.Partial) {
                PartialAccessBanner(
                    modifier = Modifier.padding(
                        horizontal = TelepicTokens.spacing.screenMargin,
                        vertical = TelepicTokens.spacing.sm,
                    ),
                    onManageAccess = controller.openAppSettings,
                )
            }
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
                // Wait for the remembered camera (so the surface opens already framed) and for the
                // first scan to have an answer. `scanSettled` is false until a scan finishes, fails,
                // or runs past its grace window, so a library that has not been read yet never
                // flashes "no locations yet" — while a genuinely long first sweep can no longer hold
                // the screen on a spinner that is indistinguishable from a hang.
                !cameraResolved || (!scanSettled && locations.isEmpty()) ->
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
                // Only claim the library holds no coordinates once the scan has settled: a sweep
                // still running may simply not have reached the geotagged files yet, and saying
                // otherwise would be a lie about the user's own photos.
                locations.isEmpty() && !isScanning -> EmptyState(
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
                        scanning = isScanning,
                        darkTiles = darkTiles,
                        onFocusConsumed = focusRequest::consume,
                        onCameraChanged = viewModel::rememberCamera,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.map_attribution),
                        style = MaterialTheme.typography.labelSmall,
                        // A link, in the link colour: the basemap is CARTO's rendering of
                        // OpenStreetMap data, and both have to be credited — with a way to reach the
                        // page that lists them, which plain text cannot offer.
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(MAP_ATTRIBUTION_URL) }
                            .padding(horizontal = spacing.md, vertical = spacing.xs),
                    )
                }
            }
            if (showLocationList) {
                PhotoLocationListDialog(
                    locations = locations,
                    onOpenMedia = onOpenMedia,
                    onDismiss = { showLocationList = false },
                )
            }
        }
    }
}

/**
 * The map's accessible counterpart: a plain list of every visible geotagged item. Canvas markers
 * cannot carry individual semantics, so this is the only way a screen-reader user can open a photo
 * from the Map tab.
 */
@Composable
private fun PhotoLocationListDialog(
    locations: List<GeotaggedMedia>,
    onOpenMedia: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_list_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(locations, key = { it.mediaId }) { item ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenMedia(item.mediaId)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.map_list_item,
                                formatCoordinate(item.location.latitude, item.location.longitude),
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.map_list_close))
            }
        },
    )
}

@Composable
private fun OsmMap(
    locations: List<GeotaggedMedia>,
    onOpenMedia: (Long) -> Unit,
    savedCamera: SavedCamera?,
    focus: MapFocus?,
    scanning: Boolean,
    darkTiles: Boolean,
    onFocusConsumed: () -> Unit,
    onCameraChanged: (SavedCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Zoom-adaptive grouping: the osmdroid zoom listener only updates this band; clusters recompute
    // when the band changes, not on every pixel of pinch — cheap for the pure clusterer either way.
    var precision by remember { mutableIntStateOf(MapZoomPrecision.DEFAULT) }
    val pins = remember(locations, precision) { MapClusterer.cluster(locations, precision) }
    // The number of *photos* the map is showing, which is what the screen's header reports and what
    // the accessibility description has to match.
    val locationCount = locations.size
    // Fit the camera to the actual photo coordinates once per distinct data set — opening the map
    // frames the user's photos instead of a fixed world view.
    //
    // The *real* viewport width, not a hard-coded one: the plan's zoom is derived from how wide the
    // map is, so a fixed 1080px framed the photos too far out on a 720px phone and too far in on a
    // tablet.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val viewportWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val plan = remember(pins, viewportWidthPx) {
        MapCameraPlan.forPoints(pins.map { it.latitude to it.longitude }, screenWidthPx = viewportWidthPx)
    }
    // The framing applied when the surface is created. Priority: an explicit focus (another screen
    // asked for one photo) → the remembered position → all the user's photos.
    //
    // Keyed on its inputs, not captured once: the map is composed before a long first scan has found
    // any coordinates (the grace window settles the scan without waiting for it), so a one-shot
    // capture froze the world view whenever the pins arrived after the surface. Recomputing lets the
    // first usable framing land when the data does; `initialApplied` still guarantees it happens once.
    val initialCamera = remember(focus, savedCamera, plan) {
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
    // The marker set only changes when `pins` does, but `AndroidView`'s `update` block runs on *every*
    // recomposition — and this screen recomposes on each pin tap and each zoom-band change. Rebuilding
    // every `Marker` and re-allocating a `Bitmap` per cluster (`MapPinBadge.clusterDrawable`) on every
    // one of those was the map's dominant cost. Identity comparison suffices: `pins` is `remember`ed
    // over the clustering inputs, so a different list instance means genuinely different pins.
    val renderedPins = remember { RenderedPins() }
    // Claimed and applied here rather than inside the AndroidView factory: a factory block runs once,
    // when the view is created, and is never re-run when `focus` changes. A request that arrived while
    // the map was already on screen was therefore neither applied nor cleared, so it replayed onto a
    // later visit the user did not ask for — the opposite of what the surface promises.
    LaunchedEffect(focus) {
        val requested = focus ?: return@LaunchedEffect
        pendingCamera = MapCameraResolver.focus(requested.latitude, requested.longitude)
        onFocusConsumed()
    }
    // Created once per surface and detached with the surface: osmdroid keeps every listener added to a
    // MapView, and the anonymous one the factory used to add survived the screen — still writing
    // Compose state from a map that had already left the composition.
    val zoomListener = remember {
        object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean = false
            override fun onZoom(event: ZoomEvent): Boolean {
                val next = MapZoomPrecision.forZoom(event.zoomLevel)
                if (next != precision) precision = next
                return false
            }
        }
    }
    // The markers keep their own fixed colours (see `MapPinBadge`) rather than following the theme.
    // A marker has to read on *both* basemaps, and a theme-derived tone would be chosen for a surface
    // the marker is not drawn on — the dark scheme's `primary` measures ~1.9:1 on light land. The pair
    // works by division of labour: the blue fill carries the marker on the light basemap, the white
    // ring carries it on the dark one, so neither has to be re-tuned per theme.
    //
    // The basemap itself does follow the theme, and is applied here rather than only in the factory
    // below: the factory runs once, so a theme flipped while the map is on screen would otherwise keep
    // the old style until the screen was recreated. `appliedTiles` is the guard that keeps this from
    // re-setting the source (and reloading every tile) on unrelated recompositions.
    val appliedTiles = remember { AppliedTileSource() }
    // The gap between tiles, and the flash while they load, used to be osmdroid's default white —
    // which on a dark basemap is a white strobe on every pan. The surface behind the map is already
    // `mediaBackdrop`, so the map's own background matches it.
    val backdropArgb = TelepicTokens.colors.mediaBackdrop.toArgb()
    LaunchedEffect(mapViewRef, darkTiles) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val target = TelepicTileSources.forDarkTheme(darkTiles)
        if (appliedTiles.source !== target) {
            mapView.setTileSource(target)
            mapView.mapOverlay.setLoadingBackgroundColor(backdropArgb)
            appliedTiles.source = target
        }
    }

    // The two camera moves, applied from an effect rather than from `AndroidView`'s `update` block.
    //
    // `update` runs during Compose's *apply* phase, so writing snapshot state there — which is what
    // clearing the pending decision used to do — is the documented misuse: every camera move
    // scheduled an extra recomposition and an extra apply pass. The view is reached through
    // [mapViewRef], which the factory fills in when the view is created.
    LaunchedEffect(mapViewRef, initialCamera) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        if (initialApplied) return@LaunchedEffect
        val decision = initialCamera as? MapCameraResolver.Decision.Apply ?: return@LaunchedEffect
        mapView.controller.setCenter(GeoPoint(decision.centerLat, decision.centerLon))
        mapView.controller.setZoom(decision.zoom)
        initialApplied = true
    }
    LaunchedEffect(mapViewRef, pendingCamera) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val decision = pendingCamera ?: return@LaunchedEffect
        if (decision is MapCameraResolver.Decision.Apply) {
            mapView.controller.setCenter(GeoPoint(decision.centerLat, decision.centerLon))
            mapView.controller.setZoom(decision.zoom)
        }
        pendingCamera = null
    }

    // Persist where the map was left. Written on pause *and* on teardown: leaving the map for another
    // tab disposes it without a pause, and that final position is the one worth remembering. Takes the
    // view rather than reading `mapViewRef`, because `onRelease` clears that reference — a capture that
    // raced it used to be a silent no-op, losing exactly the position this exists to save.
    val captureCamera: (MapView) -> Unit = { view ->
        view.mapCenter?.let { center ->
            onCameraChanged(SavedCamera(center.latitude, center.longitude, view.zoomLevelDouble))
        }
    }

    // osmdroid tiles/network only make sense while the surface is foregrounded; without this the
    // MapView keeps fetching tiles with the app backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.let { view ->
                    captureCamera(view)
                    view.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                // osmdroid is configured once in TelepicApplication (User-Agent + tile cache).
                // Calling Configuration.load() here would reset the User-Agent to the bare package
                // name on every map, which is what left the UA non-descriptive and the map blank.
                MapView(context).apply {
                    // The theme's basemap, set here so the first frame already draws the right style —
                    // the effect above only re-applies it when the theme changes while the map lives.
                    val initialTiles = TelepicTileSources.forDarkTheme(darkTiles)
                    setTileSource(initialTiles)
                    mapOverlay.setLoadingBackgroundColor(backdropArgb)
                    appliedTiles.source = initialTiles
                    setMultiTouchControls(true)
                    // This screen exists to show online tiles; be explicit rather than relying on the
                    // configuration default.
                    setUseDataConnection(true)
                    controller.setZoom(2.0)
                    setExpectedCenter(GeoPoint(20.0, 0.0))
                    addMapListener(zoomListener)
                    mapViewRef = this
                    // A freshly created MapView carries no overlays, so whatever the previous one had
                    // must not be treated as already rendered.
                    renderedPins.pins = null
                }
            },
            update = { mapView ->
                // Only rebuild the overlay list when the pins actually changed. `mapView.invalidate()`
                // below still runs every time, so camera moves and the content description stay live.
                if (renderedPins.pins !== pins) {
                    mapView.overlays.clear()
                    pins.forEach { pin ->
                        val marker = Marker(mapView)
                        marker.setPosition(GeoPoint(pin.latitude, pin.longitude))
                        when (pin) {
                            is MapClusterer.MapPin.Single -> {
                                // The app's own pin, not osmdroid's stock teardrop: the stock marker
                                // is a different colour and a different weight from the cluster
                                // bubbles, so the same map showed two unrelated visual languages.
                                marker.setIcon(MapPinBadge.singlePinDrawable(mapView.context))
                                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                // The marker's own label. osmdroid markers are canvas overlays with no
                                // accessibility node, so this is the only per-marker text osmdroid has
                                // — and the click listener below returns `true`, which suppresses
                                // osmdroid's info window, so the title is *not* what a screen reader
                                // reads either. The map view itself announces the photo count; the
                                // individual pins are reachable by touch and by the preview sheet.
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
                                // A count bubble in the same colour and with the same white ring as
                                // the single pin, so the two read as one system on the same map.
                                marker.setIcon(
                                    MapPinBadge.clusterDrawable(
                                        mapView.context,
                                        pin.size,
                                    ),
                                )
                                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                marker.title =
                                    mapView.context.resources.getQuantityString(R.plurals.map_cluster_description, pin.size, pin.size)
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
                    renderedPins.pins = pins
                }
                // TalkBack reads the map as one focusable surface: announce how many photo locations it
                // holds. The **photo** count, not the marker count: `pins` is the clustered list, so a
                // dozen photos in three clusters announced "3 photo locations" while the header said
                // "12" — two numbers for the same thing, one of them wrong.
                mapView.contentDescription =
                    mapView.context.resources.getQuantityString(
                        R.plurals.map_view_description,
                        locationCount,
                        locationCount,
                    )
                // The opening framing is applied exactly once — later recompositions (a new overlay, a
                // changed badge colour) must never move the camera out from under the user. Only a
                // real decision latches: `KeepDefault` (nothing to frame yet) leaves the flag clear so
                // the next recomposition, with the pins that finally arrived, can still apply it.
                //
                // Both camera moves are applied from the effect above rather than from `update`:
                // `update` runs during Compose's *apply* phase, and writing snapshot state there is
                // the documented misuse — it schedules an extra recomposition for every camera move.
                mapView.invalidate()
            },
            onRelease = { mapView ->
                // The last real chance to save where the user left the map — and the only place the view
                // is guaranteed still to be in hand, which is why this does not read `mapViewRef`.
                captureCamera(mapView)
                mapView.removeMapListener(zoomListener)
                mapView.onPause()
                mapView.onDetach()
                mapViewRef = null
            },
        )
        // The sweep can still be running behind a map that is already on screen, so it has to say so:
        // a partly-filled map that stays silent reads as the complete answer. Clears the moment the
        // scan settles, and is the reason the screen no longer waits on a long first sweep at all.
        if (scanning) {
            Surface(
                // `inverseSurface`, not a themed surface: this floats over *tiles*, not over a theme
                // surface, and a matching surface would be invisible on a map of the same brightness.
                // The inverse pair flips with the theme, so the chip is near-white on the dark basemap
                // and near-black on the light one — the contrast the old hardcoded white only ever
                // achieved on one of the two.
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(TelepicTokens.spacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.sm),
                    modifier = Modifier.padding(
                        horizontal = TelepicTokens.spacing.md,
                        vertical = TelepicTokens.spacing.sm,
                    ),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        // The indicator's colour has to match the chip it sits on.
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Text(
                        text = stringResource(R.string.map_scanning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        // A changing status, so it is announced rather than only seen.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        // Reframes all the photos on demand, so a stray pan is never a dead end. The regular FAB
        // (56dp) rather than the small one (40dp): Material3's FAB does not expand its own touch
        // target — `FloatingActionButtonKt` never applies `minimumInteractiveComponentSize`, and
        // neither does the clickable `Surface` it delegates to — so the small variant is a 40dp
        // target, under the 48dp accessibility floor, on this screen's only way out of a stray pan.
        FloatingActionButton(
            onClick = { pendingCamera = MapCameraResolver.recenter(plan) },
            // The inverse pair, for the reason given on the scanning chip: a FAB sits on tiles, and
            // `primaryContainer` measured 1.09:1 against a light land tile — only its glyph was
            // discernible. Following the theme this way also means the contrast holds on the dark
            // basemap, which a fixed near-white container could not do.
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
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
        //
        // Looked up through a map rather than a scan: the list is the whole geotagged library, and the
        // scan ran on every recomposition of the sheet — which is every frame of its open animation.
        val urisById = remember(locations) { locations.associate { it.mediaId to it.contentUri } }
        val contentUri = urisById[preview.mediaId]
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
 * A tapped pin's preview: what is here, and a way into the full Viewer.
 *
 * Deliberately shows only what the map actually knows (the image itself and its coordinates) — no
 * date or place is fabricated.
 *
 * The layout is a small card rather than a column of loose elements: a titled photo, the place as a
 * labelled fact, and one clear primary action. It used to be a bare cropped image with a
 * coordinate string and an icon-less button under it, which read as debug output rather than UI.
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
    // The photo's own label. It describes the *action* — the title above already names the content,
    // and the place row below already gives the position, so neither is repeated here.
    val openFullSizeLabel = stringResource(R.string.map_preview_open)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenMargin)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.map_selected_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // A heading, so the sheet can be reached by navigating between headings.
                modifier = Modifier.semantics { heading() },
            )
            // The photo, in a frame that behaves like one: a rounded surface that is visible before
            // the thumbnail arrives, a crop that keeps the picture recognisable, and an explicit
            // state for a thumbnail that is gone. It used to be a raw `AsyncImage` with no
            // placeholder at all, so the sheet showed a 180dp hole while it decoded — and a
            // 180dp-tall full-width crop that sliced a portrait photo down to a sliver.
            //
            // The frame's own states are drawn *under* the image and driven by Coil's callbacks:
            // Coil 2's `AsyncImage` takes a `Painter` for its placeholder, not a composable slot, so
            // the states are the app's own rather than a grey box.
            var frame by remember(contentUri) { mutableStateOf(MapPreviewFrame.LOADING) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PREVIEW_ASPECT_RATIO)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    // The photo is the obvious way into the Viewer, so it is a target too — a far
                    // bigger one than the button under it.
                    .clickable(role = Role.Button, onClick = { onOpen(mediaId) })
                    // Applied *after* the clickable, so the description lands on the button's own
                    // node rather than on a parent of it: a parent would leave the actionable node
                    // unlabelled, and a screen reader would meet two stops where there is one.
                    .semantics { contentDescription = openFullSizeLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (contentUri == null) {
                    MapPreviewUnavailable()
                } else {
                    when (frame) {
                        MapPreviewFrame.LOADING -> MapPreviewLoading()
                        MapPreviewFrame.UNAVAILABLE -> MapPreviewUnavailable()
                        MapPreviewFrame.READY -> Unit
                    }
                    AsyncImage(
                        model = contentUri,
                        // The title above already names what this is, so a second description here
                        // would make a screen reader say it twice.
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                        onLoading = { frame = MapPreviewFrame.LOADING },
                        onSuccess = { frame = MapPreviewFrame.READY },
                        onError = { frame = MapPreviewFrame.UNAVAILABLE },
                    )
                }
            }
            // The place, as a labelled fact rather than a floating string. Six decimals is about
            // ten centimetres — more precision than a map pin can place, and it read as a debug
            // print; five decimals is about a metre, which is what the pin is.
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = formatPlace(latitude, longitude),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // One clear primary action, stretched so it reads as the sheet's action rather than as a
            // small button floating under a wide photo.
            Button(
                onClick = { onOpen(mediaId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Photo,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(stringResource(R.string.map_selected_open))
            }
        }
    }
}

/** What the preview's frame is currently showing. */
private enum class MapPreviewFrame { LOADING, READY, UNAVAILABLE }

/** The preview's frame while the thumbnail decodes. */
@Composable
private fun MapPreviewLoading() {
    CircularProgressIndicator(
        modifier = Modifier.size(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
/** The preview's frame when there is no thumbnail to show. */
@Composable
private fun MapPreviewUnavailable() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TelepicTokens.spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.Photo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.map_preview_unavailable),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The preview's frame proportions: a landscape 4:3, so a portrait photo is cropped gently. */
private const val PREVIEW_ASPECT_RATIO = 4f / 3f

/**
 * A place label for the map's preview: five decimals, which is about a metre.
 *
 * The shared [formatCoordinate] uses six — about ten centimetres — because the Viewer's details
 * sheet is reporting a *measured* fact. On a map pin that precision is noise: the pin cannot place
 * anything better than a metre, and the longer string read as debug output.
 */
private fun formatPlace(latitude: Double, longitude: Double): String {
    val ns = if (latitude >= 0) "N" else "S"
    val ew = if (longitude >= 0) "E" else "W"
    return String.format(
        Locale.US,
        "%.5f%s, %.5f%s",
        kotlin.math.abs(latitude), ns,
        kotlin.math.abs(longitude), ew,
    )
}

private const val MAX_ZOOM = 19.0

/**
 * Where the map attribution links to.
 *
 * The basemap is CARTO's rendering of OpenStreetMap data, so both have to be credited — and CARTO's
 * attribution page is the one that lists both, which is why the link points there rather than at
 * `openstreetmap.org/copyright` (the correct target when we fetched OSM tiles directly).
 */
private const val MAP_ATTRIBUTION_URL = "https://carto.com/attributions"

/**
 * Remembers which pin list the map's overlays currently represent.
 *
 * A plain holder rather than `mutableStateOf`: it is only ever read and written inside
 * `AndroidView`'s `update` block, so making it observable would schedule a recomposition for a value
 * composition never reads.
 */
private class RenderedPins {
    var pins: List<MapClusterer.MapPin>? = null
}

/**
 * Remembers which basemap the live `MapView` is currently showing.
 *
 * Same reasoning as [RenderedPins], plus one of its own: `setTileSource` throws away the in-memory
 * tile list and reloads, so re-applying the same source on every recomposition would re-fetch tiles
 * continuously. Comparing the instance is enough because the two sources are singletons.
 */
private class AppliedTileSource {
    var source: OnlineTileSourceBase? = null
}
