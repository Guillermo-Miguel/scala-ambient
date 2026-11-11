package es.scala.ambient.ui

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import es.scala.ambient.AmbientApp
import es.scala.ambient.Place
import es.scala.ambient.nav.Route
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Mapa vectorial (MapLibre) con el estilo oscuro de OpenFreeMap: nítido a cualquier
 * resolución y sin clave de API. Datos © OpenStreetMap.
 */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** Lo que el mapa necesita pintar. `recenter` cambia cuando hay que volver a encuadrar. */
data class MapScene(
    val destination: Place?,
    val route: Route?,
    val recenter: Int,
    val centerOnMe: Boolean = false,
    /** Navegando: seguir al coche con el mapa girado e inclinado en el sentido de la marcha. */
    val nav: NavCamera? = null,
    /** El usuario ha movido el mapa con el dedo. */
    val onUserPan: (() -> Unit)? = null,
)

/** `zoom` en la escala de siempre (15–18 ≈ calle). */
data class NavCamera(val lat: Double, val lon: Double, val bearing: Float, val zoom: Double, val follow: Boolean)

/** Última vista de cada mapa (el grande y el de Inicio): al volver, aparece donde estaba. */
private object MapCamera {
    val saved = HashMap<Boolean, CameraPosition>()
}

/** MapLibre usa teselas de 512 px: un nivel menos de zoom para ver lo mismo que antes. */
private fun z(level: Double) = level - 1.0

class MapHandle {
    var view: MapView? = null
    var map: MapLibreMap? = null
    var style: Style? = null
    var scene: MapScene? = null
    var me: Pair<Double, Double>? = null
    var lastRecenter = -1
    var lastNavMove = 0L
}

private fun lineOf(points: List<Pair<Double, Double>>): FeatureCollection =
    if (points.size < 2) FeatureCollection.fromFeatures(arrayOf<Feature>())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })))

private fun pointOf(p: Pair<Double, Double>?): FeatureCollection =
    if (p == null) FeatureCollection.fromFeatures(arrayOf<Feature>())
    else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.second, p.first)))

/** Añade la capa por debajo de los nombres de calles para que estos queden encima. */
private fun Style.addBelowLabels(layer: Layer) {
    runCatching { addLayerBelow(layer, "highway_name_other") }.onFailure { addLayer(layer) }
}

private fun setupStyle(style: Style) {
    style.addSource(GeoJsonSource("route"))
    style.addSource(GeoJsonSource("dest"))
    style.addSource(GeoJsonSource("me"))
    style.addBelowLabels(
        LineLayer("route-casing", "route").withProperties(
            PropertyFactory.lineColor("#000000"), PropertyFactory.lineOpacity(0.7f), PropertyFactory.lineWidth(13f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addBelowLabels(
        LineLayer("route-line", "route").withProperties(
            PropertyFactory.lineColor("#F5F5F7"), PropertyFactory.lineWidth(7f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    style.addLayer(
        CircleLayer("me-halo", "me").withProperties(
            PropertyFactory.circleRadius(20f), PropertyFactory.circleColor("#E4002B"), PropertyFactory.circleOpacity(0.18f),
        )
    )
    style.addLayer(
        CircleLayer("me-dot", "me").withProperties(
            PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor("#E4002B"), PropertyFactory.circleStrokeWidth(3f),
        )
    )
    style.addLayer(
        CircleLayer("dest-dot", "dest").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#E4002B"),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(3f),
        )
    )
}

/** Pinta la escena y mueve la cámara si hace falta. */
private fun apply(h: MapHandle) {
    val map = h.map ?: return
    val style = h.style ?: return
    val scene = h.scene ?: return
    val nav = scene.nav

    style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(lineOf(scene.route?.points.orEmpty()))
    style.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(pointOf(scene.destination?.let { it.lat to it.lon }))
    // con el coche seguido se dibuja la flecha encima del mapa; si no, el punto de tu posición
    style.getSourceAs<GeoJsonSource>("me")?.setGeoJson(pointOf(if (nav != null && nav.follow) null else h.me))

    // navegación: seguir al coche, girado e inclinado
    if (nav != null && nav.follow) {
        val now = System.currentTimeMillis()
        if (now - h.lastNavMove > 400) {
            h.lastNavMove = now
            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(nav.lat, nav.lon)).zoom(z(nav.zoom)).bearing(nav.bearing.toDouble()).tilt(45.0).build()
                ),
                900,
            )
        }
        h.lastRecenter = scene.recenter
        return
    }

    if (h.lastRecenter == -1) { h.lastRecenter = scene.recenter; return }      // primera vez: no mover
    if (scene.recenter == h.lastRecenter) return
    h.lastRecenter = scene.recenter

    val me = h.me
    val pts = scene.route?.points
    when {
        scene.centerOnMe && me != null -> map.animateCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(me.first, me.second)).zoom(z(16.5)).bearing(0.0).tilt(0.0).build()), 700,
        )
        pts != null && pts.size > 1 -> {
            val b = LatLngBounds.Builder().includes(pts.map { LatLng(it.first, it.second) }).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 140), 700)
        }
        scene.destination != null -> map.animateCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(scene.destination.lat, scene.destination.lon)).zoom(z(15.5)).bearing(0.0).tilt(0.0).build()), 700,
        )
        me != null -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(me.first, me.second), z(15.5)), 700)
    }
}

@Composable
fun OsmMap(scene: MapScene, interactive: Boolean, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handle = remember { MapHandle() }
    val here by AmbientApp.instance.location.location.collectAsState()

    // en el coche: pellizcar o doble toque para acercar/alejar
    val carMode = LocalCarMode.current
    LaunchedEffect(carMode, interactive) {
        if (carMode && interactive) es.scala.ambient.car.CarBus.zoom.collect { f ->
            if (f > 0f) handle.map?.animateCamera(CameraUpdateFactory.zoomBy(kotlin.math.log2(f.toDouble())), 250)
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> handle.view?.onStart()
                Lifecycle.Event.ON_RESUME -> handle.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> handle.view?.onPause()
                Lifecycle.Event.ON_STOP -> handle.view?.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            handle.map?.let { MapCamera.saved[interactive] = CameraPosition.Builder(it.cameraPosition).tilt(0.0).bearing(0.0).build() }
            handle.view?.apply { onPause(); onStop(); onDestroy() }
            handle.view = null; handle.map = null; handle.style = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).also { mv ->
                handle.view = mv
                mv.onCreate(null)
                mv.onStart()
                mv.onResume()
                mv.getMapAsync { map ->
                    handle.map = map
                    map.uiSettings.apply {
                        setAllGesturesEnabled(interactive)
                        isCompassEnabled = false
                        isLogoEnabled = false
                        isAttributionEnabled = true
                        setAttributionTintColor(0x66FFFFFF)
                        attributionGravity = Gravity.BOTTOM or Gravity.END
                        setAttributionMargins(0, 0, 18, 12)
                    }
                    // empezar ya en el sitio correcto: la última vista, o tu posición, o (sin datos) Madrid
                    val loc = AmbientApp.instance.location.location.value
                    map.cameraPosition = MapCamera.saved[interactive]
                        ?: CameraPosition.Builder()
                            .target(loc?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(40.4168, -3.7038))
                            .zoom(z(if (interactive) 15.5 else 13.5))
                            .build()
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) handle.scene?.onUserPan?.invoke()
                    }
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                        setupStyle(style)
                        handle.style = style
                        apply(handle)
                    }
                }
            }
        },
        update = {
            handle.scene = scene
            handle.me = here?.let { it.latitude to it.longitude }
            apply(handle)
        },
    )
}
