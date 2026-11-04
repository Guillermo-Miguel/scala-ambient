package es.scala.ambient.ui

import android.graphics.Color as AColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import es.scala.ambient.AmbientApp
import es.scala.ambient.Place
import es.scala.ambient.R
import es.scala.ambient.nav.Route
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * Mapa de OpenStreetMap (sin clave de API) pasado a blanco y negro e invertido,
 * para que quede oscuro como el resto del sistema.
 */
private val DarkMapFilter: ColorMatrixColorFilter = run {
    val m = ColorMatrix().apply { setSaturation(0f) }                     // blanco y negro
    m.postConcat(ColorMatrix(floatArrayOf(                               // invertir
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )))
    m.postConcat(ColorMatrix(floatArrayOf(                               // un poco más apagado
        0.82f, 0f, 0f, 0f, 0f,
        0f, 0.82f, 0f, 0f, 0f,
        0f, 0f, 0.82f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    ColorMatrixColorFilter(m)
}

/** Lo que el mapa necesita pintar. `recenter` cambia cuando hay que volver a encuadrar. */
data class MapScene(
    val destination: Place?,
    val route: Route?,
    val recenter: Int,
    val centerOnMe: Boolean = false,
    /** Navegando: seguir al coche con el mapa girado en el sentido de la marcha. */
    val nav: NavCamera? = null,
    /** El usuario ha movido el mapa con el dedo. */
    val onUserPan: (() -> Unit)? = null,
)

data class NavCamera(val lat: Double, val lon: Double, val bearing: Float, val zoom: Double, val follow: Boolean)

/**
 * Última vista de cada mapa (el grande y el de Inicio). Al volver a una pantalla,
 * el mapa aparece directamente donde estaba, sin viajar desde otro sitio.
 */
private object MapCamera {
    val saved = HashMap<Boolean, Triple<Double, Double, Double>>()   // interactive → (lat, lon, zoom)
}

class MapHandle {
    var view: MapView? = null
    var me: MyLocationNewOverlay? = null
    var line: Polyline? = null
    var marker: Marker? = null
    var lastRecenter = -1
    var onPan: (() -> Unit)? = null
    var restored = false
}

@Composable
fun OsmMap(scene: MapScene, interactive: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handle = remember { MapHandle() }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { handle.view?.onResume(); handle.me?.enableMyLocation() }
                Lifecycle.Event.ON_PAUSE -> { handle.view?.onPause(); handle.me?.disableMyLocation() }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            handle.view?.let { v ->
                MapCamera.saved[interactive] = Triple(v.mapCenter.latitude, v.mapCenter.longitude, v.zoomLevelDouble)
            }
            lifecycle.removeObserver(obs)
            handle.me?.disableMyLocation()
            handle.view?.onDetach()
            handle.view = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                overlayManager.tilesOverlay.setColorFilter(DarkMapFilter)
                overlayManager.tilesOverlay.setLoadingBackgroundColor(AColor.BLACK)
                overlayManager.tilesOverlay.setLoadingLineColor(AColor.rgb(20, 20, 22))
                setMultiTouchControls(interactive)
                isClickable = interactive
                isTilesScaledToDpi = true
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setBackgroundColor(AColor.BLACK)
                // empezar ya en el sitio correcto: la última vista, o tu posición, o (sin datos) Madrid
                val saved = MapCamera.saved[interactive]
                val here = AmbientApp.instance.location.location.value
                handle.restored = saved != null || here != null
                controller.setZoom(saved?.third ?: if (interactive) 15.5 else 13.5)
                controller.setCenter(
                    when {
                        saved != null -> GeoPoint(saved.first, saved.second)
                        here != null -> GeoPoint(here.latitude, here.longitude)
                        else -> GeoPoint(40.4168, -3.7038)
                    }
                )
                if (!interactive) setOnTouchListener { _, _ -> true }   // vista previa fija

                val me = MyLocationNewOverlay(GpsMyLocationProvider(ctx), this).apply {
                    enableMyLocation()
                    // solo si no sabíamos dónde estabas: ir a tu posición en cuanto llegue el GPS
                    runOnFirstFix { post { if (!handle.restored && handle.line == null) controller.setCenter(myLocation) } }
                }
                overlays.add(me)
                // detectar cuando mueves el mapa con el dedo (para dejar de seguir al coche)
                overlays.add(object : Overlay() {
                    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                        if (event.action == MotionEvent.ACTION_MOVE) handle.onPan?.invoke()
                        return false
                    }
                })
                handle.me = me
                handle.view = this
                onResume()
            }
        },
        update = { map ->
            // ruta
            handle.line?.let { map.overlays.remove(it) }
            handle.line = null
            scene.route?.let { r ->
                val pl = Polyline(map).apply {
                    setPoints(r.points.map { GeoPoint(it.first, it.second) })
                    outlinePaint.color = AColor.WHITE
                    outlinePaint.strokeWidth = 11f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                    outlinePaint.isAntiAlias = true
                }
                map.overlays.add(0, pl)
                handle.line = pl
            }
            // destino
            handle.marker?.let { map.overlays.remove(it) }
            handle.marker = null
            scene.destination?.let { d ->
                val mk = Marker(map).apply {
                    position = GeoPoint(d.lat, d.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_pin)
                    title = d.name
                    setInfoWindow(null)
                }
                map.overlays.add(mk)
                handle.marker = mk
            }
            // navegación: el mapa sigue al coche y gira con él
            handle.onPan = scene.onUserPan
            val nav = scene.nav
            handle.me?.isEnabled = nav == null || !nav.follow
            if (nav != null && nav.follow) {
                if (kotlin.math.abs(map.mapOrientation + nav.bearing) > 2f) map.mapOrientation = -nav.bearing
                map.controller.animateTo(GeoPoint(nav.lat, nav.lon), nav.zoom, 900L)
                handle.lastRecenter = scene.recenter
                map.invalidate()
                return@AndroidView
            }
            if (nav == null && map.mapOrientation != 0f) map.mapOrientation = 0f

            // encuadre
            if (handle.lastRecenter == -1) {
                // primera vez: no mover la vista (ya empieza donde toca)
                handle.lastRecenter = scene.recenter
            } else if (scene.recenter != handle.lastRecenter) {
                handle.lastRecenter = scene.recenter
                val pts = scene.route?.points
                val me = handle.me?.myLocation
                if (scene.centerOnMe && me != null) {
                    map.controller.animateTo(me, 16.5, 700L)
                } else if (pts != null && pts.size > 1) {
                    val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.first, it.second) })
                    map.post { map.zoomToBoundingBox(box.increaseByScale(1.35f), true, 80) }
                } else if (scene.destination != null) {
                    map.controller.animateTo(GeoPoint(scene.destination.lat, scene.destination.lon), 15.5, 700L)
                } else {
                    handle.me?.myLocation?.let { map.controller.animateTo(it, 15.5, 700L) }
                }
            }
            map.invalidate()
        },
    )
}
