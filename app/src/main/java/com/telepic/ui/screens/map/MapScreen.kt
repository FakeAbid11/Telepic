package com.telepic.ui.screens.map

import android.content.Context
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
import com.telepic.diagnostics.TelepicLog
import com.telepic.domain.media.GeoLocation
import com.telepic.domain.media.GeotaggedMedia
import com.telepic.map.BasemapStyles
import com.telepic.map.MapLibreConfiguration
import com.telepic.map.MapPinIcons
import com.telepic.map.MapPinsGeoJson
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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

/**
 * The interactive photo map (Phase 10). Renders geotagged local media as markers on a MapLibre
 * surface, grouped by the pure [MapClusterer] at a grid precision that follows the current zoom
 * ([MapZoomPrecision]) — zoom in, clusters split. Clusters render a drawn count badge; tapping a
 * single pin centres the map on it and opens a preview sheet offering the full Viewer.
 *
 * Basemap: OpenFreeMap vector styles ([BasemapStyles]), one light and one dark, following the app's
 * own theme. Free, unlimited, and **keyless** — which is the whole reason for both the provider and
 * the engine: osmdroid can only draw raster tiles, and no raster provider offers light + dark without
 * a key. Coordinates and EXIF are NEVER uploaded, and no location is shown for media that lacks GPS.
 *
 * The surface is device-bound: pan/zoom, style loading and marker rendering are validated on a real
 * device and are NOT exercised by Robolectric. The map *logic* (GPS parsing, clustering,
 * zoom→precision, selection→identity, camera policy) lives behind pure, unit-tested seams — and the
 * GeoJSON mapping is pure too ([MapPinsGeoJson]).
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
    val darkBasemap = LocalDarkTheme.current

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
                    PhotoMap(
                        locations = locations,
                        onOpenMedia = onOpenMedia,
                        savedCamera = savedCamera,
                        focus = focus,
                        scanning = isScanning,
                        darkBasemap = darkBasemap,
                        onFocusConsumed = focusRequest::consume,
                        onCameraChanged = viewModel::rememberCamera,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = BasemapStyles.ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall,
                        // A link, in the link colour: OpenFreeMap serves the tiles, OpenMapTiles
                        // defines the schema and OpenStreetMap supplies the data, so all three have
                        // to be credited — with a way to reach the page that lists them, which
                        // plain text cannot offer.
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(BasemapStyles.ATTRIBUTION_URL) }
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
private fun PhotoMap(
    locations: List<GeotaggedMedia>,
    onOpenMedia: (Long) -> Unit,
    savedCamera: SavedCamera?,
    focus: MapFocus?,
    scanning: Boolean,
    darkBasemap: Boolean,
    onFocusConsumed: () -> Unit,
    onCameraChanged: (SavedCamera) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Zoom-adaptive grouping: the camera-idle listener only updates this band; clusters recompute
    // when the band changes, not on every pixel of pinch — cheap for the pure clusterer either way.
    // MapLibre separates continuous camera moves from settled ones, and the idle callback is the
    // faithful equivalent of the previous engine's zoom listener: it fires when the gesture ends,
    // not on every frame of it.
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
    // Two references, because they become available at different moments: the view exists as soon as
    // the factory returns, the map only inside `getMapAsync`. The camera effects key on the map, so
    // they run once it is genuinely ready instead of racing it.
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    // Owns the style/pins ordering — see [MapSurface].
    val surface = remember { MapSurface() }
    // The marker set only changes when `pins` does, and this screen recomposes on each pin tap and
    // each zoom-band change. MapLibre re-renders a source from a `FeatureCollection` rather than from
    // per-marker objects, so the cost moved: what is guarded now is rebuilding that collection and
    // re-registering the cluster bitmaps. [MapSurface] compares the list instance, which suffices
    // because `pins` is `remember`ed over the clustering inputs.
    val styleLoadedListener = remember {
        MapView.OnDidFinishLoadingStyleListener { surface.onStyleLoaded() }
    }
    // Claimed and applied here rather than inside the AndroidView factory: a factory block runs once,
    // when the view is created, and is never re-run when `focus` changes. A request that arrived while
    // the map was already on screen was therefore neither applied nor cleared, so it replayed onto a
    // later visit the user did not ask for — the opposite of what the surface promises.
    LaunchedEffect(focus) {
        val requested = focus ?: return@LaunchedEffect
        pendingCamera = MapCameraResolver.focus(requested.latitude, requested.longitude)
        onFocusConsumed()
    }
    // Created once per surface and detached with the surface: MapLibre keeps every listener added to a
    // map, and an anonymous one created in the factory survived the screen — still writing Compose
    // state from a map that had already left the composition.
    val cameraIdleListener = remember {
        MapLibreMap.OnCameraIdleListener {
            val zoom = mapRef?.cameraPosition?.zoom ?: return@OnCameraIdleListener
            val next = MapZoomPrecision.forZoom(zoom)
            if (next != precision) precision = next
        }
    }
    // Hit-testing. MapLibre has no per-feature click callback on the map object, so a tap is resolved
    // by projecting the tapped coordinate to a screen point and asking the style which rendered
    // feature sits under it — restricted to the two pin layers, so a tap on a road hits nothing.
    val clickListener = remember {
        MapLibreMap.OnMapClickListener { latLng ->
            val map = mapRef ?: return@OnMapClickListener false
            val point = map.projection.toScreenLocation(latLng)
            val feature = map
                .queryRenderedFeatures(point, SINGLE_LAYER_ID, CLUSTER_LAYER_ID)
                .firstOrNull()
                ?: return@OnMapClickListener false
            val geometry = feature.geometry() as? Point ?: return@OnMapClickListener false
            when (feature.getStringProperty(MapPinsGeoJson.PROP_KIND)) {
                MapPinsGeoJson.KIND_SINGLE -> {
                    val mediaId = feature
                        .getStringProperty(MapPinsGeoJson.PROP_MEDIA_ID)
                        ?.toLongOrNull()
                        ?: return@OnMapClickListener false
                    // Centre and offer a preview rather than throwing the user straight into the
                    // Viewer — the map keeps its context behind the sheet.
                    pendingCamera = MapCameraResolver.focus(geometry.latitude(), geometry.longitude())
                    selected = MapClusterer.MapPin.Single(
                        latitude = geometry.latitude(),
                        longitude = geometry.longitude(),
                        mediaId = mediaId,
                    )
                    true
                }
                MapPinsGeoJson.KIND_CLUSTER -> {
                    // Centre on the cluster, then zoom in — the recluster follows via the camera-idle
                    // listener; no zoom level is invented here.
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(geometry.latitude(), geometry.longitude()),
                            (map.cameraPosition.zoom + CLUSTER_ZOOM_STEP)
                                .coerceAtMost(BasemapStyles.MAX_ZOOM),
                        ),
                    )
                    true
                }
                else -> false
            }
        }
    }
    // The markers keep their own fixed colours (see `MapPinBadge`) rather than following the theme.
    // A marker has to read on *both* basemaps, and a theme-derived tone would be chosen for a surface
    // the marker is not drawn on — the dark scheme's `primary` measures ~1.9:1 on light land. The pair
    // works by division of labour: the blue fill carries the marker on the light basemap, the white
    // ring carries it on the dark one, so neither has to be re-tuned per theme.
    //
    // The basemap itself follows the theme, and is applied from an effect rather than only in the
    // factory: the factory runs once, so a theme flipped while the map is on screen would otherwise
    // keep the old style until the screen was recreated. `MapSurface.requestStyle` is the guard that
    // stops an unchanged theme from reloading the style — and every tile with it — on an unrelated
    // recomposition.
    //
    // The gap colour matters more here than it did on raster: MapLibre paints its own background
    // while a style loads and the default is light grey, which is a white flash on the dark basemap.
    // The surface behind the map is already `mediaBackdrop`, so the map's own background matches it.
    val backdropArgb = TelepicTokens.colors.mediaBackdrop.toArgb()
    LaunchedEffect(mapRef, darkBasemap) {
        val map = mapRef ?: return@LaunchedEffect
        surface.requestStyle(map, BasemapStyles.forDarkTheme(darkBasemap))
    }
    // Pins, independently of the style: `MapSurface` holds them until a style is ready, so it does not
    // matter whether the scan finishes before or after the basemap loads.
    LaunchedEffect(pins) { surface.updatePins(pins) }

    // The two camera moves, applied from an effect rather than from `AndroidView`'s `update` block.
    //
    // `update` runs during Compose's *apply* phase, so writing snapshot state there — which is what
    // clearing the pending decision used to do — is the documented misuse: every camera move
    // scheduled an extra recomposition and an extra apply pass. The map is reached through
    // [mapRef], which `getMapAsync` fills in once the surface is actually ready.
    LaunchedEffect(mapRef, initialCamera) {
        val map = mapRef ?: return@LaunchedEffect
        if (initialApplied) return@LaunchedEffect
        val decision = initialCamera as? MapCameraResolver.Decision.Apply ?: return@LaunchedEffect
        map.moveCamera(decision.toCameraUpdate())
        // Set the band from the target zoom rather than waiting for the idle callback: the first
        // framing must cluster at the right precision on the frame it lands, not one callback later.
        precision = MapZoomPrecision.forZoom(decision.zoom)
        initialApplied = true
    }
    LaunchedEffect(mapRef, pendingCamera) {
        val map = mapRef ?: return@LaunchedEffect
        val decision = pendingCamera ?: return@LaunchedEffect
        if (decision is MapCameraResolver.Decision.Apply) {
            map.moveCamera(decision.toCameraUpdate())
            precision = MapZoomPrecision.forZoom(decision.zoom)
        }
        pendingCamera = null
    }

    // Persist where the map was left. Written on pause *and* on teardown: leaving the map for another
    // tab disposes it without a pause, and that final position is the one worth remembering.
    //
    // Reads the map, not the view, and treats a null `target` as "nothing to save": MapLibre reports
    // no camera target before the first framing rather than a fabricated (0, 0), so a capture that
    // early saves nothing instead of saving Null Island.
    val captureCamera: () -> Unit = {
        mapRef?.let { map ->
            val position = map.cameraPosition
            position.target?.let { target ->
                onCameraChanged(SavedCamera(target.latitude, target.longitude, position.zoom))
            }
        }
    }

    // MapLibre needs the same foreground signal the previous engine did, but it splits it further:
    // `onStart` is what activates the tile FileSource and the connectivity receiver, so without it no
    // tile is ever fetched. `onDestroy` in `onRelease` is the replacement for osmdroid's `onDetach`.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapViewRef?.onStart()
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    captureCamera()
                    mapViewRef?.onPause()
                }
                Lifecycle.Event.ON_STOP -> mapViewRef?.onStop()
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
                // MapLibre must be initialised before the first MapView, and this is the only place a
                // MapView is ever constructed. Deliberately not in the Application: every Robolectric
                // test boots that, and a MapView loads a native library a JVM test cannot have.
                MapLibreConfiguration.ensureInitialized(context)
                val options = MapLibreMapOptions.createFromAttributes(context)
                    // The replacement for the previous engine's loading background: MapLibre paints
                    // its own grey while a style loads, which is a white flash on the dark basemap.
                    .foregroundLoadColor(backdropArgb)
                MapView(context, options).apply {
                    onCreate(null)
                    addOnDidFinishLoadingStyleListener(styleLoadedListener)
                    addOnDidFailLoadingMapListener { message ->
                        TelepicLog.warn("Map", "the map failed to load: $message")
                    }
                    // This destination is already resumed when the view is created, so the observer
                    // above never sees START/RESUME for it. MapView guards both calls, and `onStart`
                    // is what activates tile loading — without this replay the first map is blank.
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        onStart()
                    }
                    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        onResume()
                    }
                    getMapAsync { map ->
                        // Gesture parity with the previous engine: MapLibre rotates and tilts by
                        // default, which osmdroid never did, and a map that spins under a two-finger
                        // pan is a different map. The compass exists to undo rotation, so it goes
                        // with it — this screen's design never had one.
                        map.uiSettings.setRotateGesturesEnabled(false)
                        map.uiSettings.setTiltGesturesEnabled(false)
                        map.uiSettings.setCompassEnabled(false)
                        // The screen draws its own attribution below the surface, because that credit
                        // has to be a link; MapLibre's overlay is a second, unlinked copy of it.
                        map.uiSettings.setAttributionEnabled(false)
                        map.uiSettings.setLogoEnabled(false)
                        map.setMinZoomPreference(BasemapStyles.MIN_ZOOM)
                        map.setMaxZoomPreference(BasemapStyles.MAX_ZOOM)
                        // A neutral default that the first framing replaces — never a fabricated
                        // location, and the same world view the previous engine was given.
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(NEUTRAL_LAT, NEUTRAL_LON),
                                NEUTRAL_ZOOM,
                            ),
                        )
                        map.addOnMapClickListener(clickListener)
                        map.addOnCameraIdleListener(cameraIdleListener)
                        surface.onMapReady(map, context)
                        mapRef = map
                    }
                    // A freshly created view has no style and no layers, so whatever the previous one
                    // had must not be treated as already rendered. The pins themselves are kept.
                    surface.reset()
                    mapViewRef = this
                }
            },
            update = { view ->
                // The pins are pushed by the `LaunchedEffect(pins)` above and drawn by
                // `MapSurface` once a style exists, so `update` has nothing to rebuild — which is the
                // point. It carries the one thing that must track composition: the description.
                //
                // TalkBack reads the map as one focusable surface: announce how many photo locations
                // it holds. The **photo** count, not the marker count: `pins` is the clustered list,
                // so a dozen photos in three clusters announced "3 photo locations" while the header
                // said "12" — two numbers for the same thing, one of them wrong.
                view.contentDescription = view.resources.getQuantityString(
                    R.plurals.map_view_description,
                    locationCount,
                    locationCount,
                )
                // No `invalidate()`: MapLibre redraws itself, and the opening framing is applied
                // exactly once from the effect above — a later recomposition (a new pin, a changed
                // badge colour) must never move the camera out from under the user.
            },
            onRelease = { view ->
                // The last real chance to save where the user left the map — and the only place the
                // view is guaranteed still to be in hand, which is why this does not read
                // `mapViewRef`.
                captureCamera()
                view.removeOnDidFinishLoadingStyleListener(styleLoadedListener)
                mapRef?.let { map ->
                    map.removeOnMapClickListener(clickListener)
                    map.removeOnCameraIdleListener(cameraIdleListener)
                }
                // `onDestroy` is the replacement for the previous engine's `onDetach`: leaving the Map
                // tab disposes the surface while the activity stays alive, so the renderer has to be
                // torn down here or the next open leaks it.
                if (!view.isDestroyed()) {
                    view.onPause()
                    view.onStop()
                    view.onDestroy()
                }
                surface.reset()
                mapRef = null
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

/** How far a tap on a cluster zooms in. Two levels, as it was before the engine changed. */
private const val CLUSTER_ZOOM_STEP = 2.0

/**
 * The camera before anything has framed the photos.
 *
 * The same neutral world view the previous engine was given, one zoom lower: MapLibre's camera zoom
 * is defined on a 512 px world grid where raster's was 256, so `raster 2` is `MapLibre 1`.
 */
private const val NEUTRAL_LAT = 20.0
private const val NEUTRAL_LON = 0.0
private const val NEUTRAL_ZOOM = 1.0

private const val PINS_SOURCE_ID = "telepic-pins"
private const val SINGLE_LAYER_ID = "telepic-pins-single"
private const val CLUSTER_LAYER_ID = "telepic-pins-cluster"

/** The camera update for a resolved framing decision. */
private fun MapCameraResolver.Decision.Apply.toCameraUpdate() =
    CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLon), zoom)

/**
 * The single-photo layer.
 *
 * The icon id comes from the feature, so one image draws every single pin. The two overlap flags are
 * load-bearing: MapLibre resolves symbol collisions by default and would silently hide any pin that
 * sits near another — the opposite of what a photo map is for. The previous engine drew every marker
 * unconditionally, so this restores that.
 */
private fun singlePinLayer(): SymbolLayer = SymbolLayer(SINGLE_LAYER_ID, PINS_SOURCE_ID)
    .withFilter(Expression.eq(Expression.get(MapPinsGeoJson.PROP_KIND), MapPinsGeoJson.KIND_SINGLE))
    .withProperties(
        PropertyFactory.iconImage(Expression.get(MapPinsGeoJson.PROP_ICON)),
        // The pin's tip sits on the coordinate, as the marker anchor did.
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )

/** The cluster layer. A bubble is centred on its location rather than pointing at it. */
private fun clusterPinLayer(): SymbolLayer = SymbolLayer(CLUSTER_LAYER_ID, PINS_SOURCE_ID)
    .withFilter(Expression.eq(Expression.get(MapPinsGeoJson.PROP_KIND), MapPinsGeoJson.KIND_CLUSTER))
    .withProperties(
        PropertyFactory.iconImage(Expression.get(MapPinsGeoJson.PROP_ICON)),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
    )

/**
 * Owns everything that has to wait for a style: the images, the pin source, the two layers, and the
 * ordering between "the pins arrived" and "the style loaded".
 *
 * A plain holder rather than Compose state: it is written from MapLibre callbacks and read from
 * effects, so making it observable would recompose the screen for values composition never reads.
 *
 * Two ordering cases it exists to absorb, because they race every time the map opens:
 * - **Pins before style.** `updatePins` stores the list; `renderPins` returns early because there is
 *   no style yet; the style callback renders the stored list.
 * - **Style before pins.** The source and layers are created empty, and the later `updatePins` pushes
 *   the features into the existing source.
 *
 * `pins` survives [reset] deliberately. The surface is destroyed and rebuilt whenever the user leaves
 * the Map tab and returns, and a rebuilt view must draw the current pins on its first style load —
 * otherwise the map comes back blank until the next scan.
 */
private class MapSurface {
    private var map: MapLibreMap? = null
    private var context: Context? = null
    private var style: Style? = null
    private var requestedUrl: String? = null
    private var pins: List<MapClusterer.MapPin> = emptyList()
    private var rendered: List<MapClusterer.MapPin>? = null
    private val imageIds = mutableSetOf<String>()

    fun onMapReady(map: MapLibreMap, context: Context) {
        this.map = map
        this.context = context
    }

    fun reset() {
        map = null
        context = null
        style = null
        requestedUrl = null
        rendered = null
        imageIds.clear()
    }

    fun updatePins(pins: List<MapClusterer.MapPin>) {
        this.pins = pins
        renderPins()
    }

    fun requestStyle(map: MapLibreMap, url: String) {
        // The guard that keeps an unrelated recomposition from reloading the style and every tile.
        if (url == requestedUrl) return
        requestedUrl = url
        // Everything style-scoped is about to be thrown away by MapLibre.
        style = null
        rendered = null
        imageIds.clear()
        map.setStyle(Style.Builder().fromUri(url))
    }

    fun onStyleLoaded() {
        val live = map ?: return
        // The guard that makes this safe: `getStyle()` returns null unless a style is *fully* loaded,
        // and every Style mutator throws while a newer style is still loading. A stale "loaded" event
        // from a superseded style is therefore a no-op.
        val loaded = live.getStyle() ?: return
        if (loaded !== style) {
            style = loaded
            rendered = null
            imageIds.clear()
        }
        renderPins()
    }

    private fun renderPins() {
        // Named apart from the `style` field on purpose: `val style = style ?: return` compiles but
        // shadows, and every line below would then read as if it were touching the field.
        val live = style ?: return
        val context = context ?: return
        if (rendered === pins) return

        // One image per distinct cluster size in this pass, plus the single pin.
        val counts = pins.filterIsInstance<MapClusterer.MapPin.Cluster>().map { it.size }.distinct()
        val wanted = buildSet {
            add(MapPinIcons.SINGLE)
            counts.forEach { add(MapPinIcons.cluster(it)) }
        }
        if (MapPinIcons.SINGLE !in imageIds) {
            live.addImage(MapPinIcons.SINGLE, MapPinBadge.singlePinBitmap(context))
            imageIds += MapPinIcons.SINGLE
        }
        counts.forEach { count ->
            val id = MapPinIcons.cluster(count)
            if (id !in imageIds) {
                live.addImage(id, MapPinBadge.clusterBitmap(context, count))
                imageIds += id
            }
        }

        val collection = MapPinsGeoJson.featureCollection(pins)
        val source = live.getSource(PINS_SOURCE_ID) as? GeoJsonSource
        if (source == null) {
            live.addSource(GeoJsonSource(PINS_SOURCE_ID, collection))
        } else {
            source.setGeoJson(collection)
        }
        if (live.getLayer(SINGLE_LAYER_ID) == null) live.addLayer(singlePinLayer())
        if (live.getLayer(CLUSTER_LAYER_ID) == null) live.addLayer(clusterPinLayer())

        // A cluster size that no longer occurs would otherwise leave its bitmap registered forever,
        // one per size ever seen.
        val stale = imageIds - wanted
        stale.forEach { id ->
            live.removeImage(id)
            imageIds -= id
        }
        rendered = pins
    }
}
