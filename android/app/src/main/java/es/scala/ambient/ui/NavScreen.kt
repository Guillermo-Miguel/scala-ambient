package es.scala.ambient.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.Place
import es.scala.ambient.NavApp
import es.scala.ambient.nav.NavModel
import es.scala.ambient.nav.NavSession
import es.scala.ambient.nav.NavState
import es.scala.ambient.nav.Route
import es.scala.ambient.nav.Net
import es.scala.ambient.nav.formatDistance
import es.scala.ambient.nav.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun NavScreen(wide: Boolean) {
    // guiando: pantalla de navegación paso a paso
    val guiding by NavSession.state.collectAsState()
    guiding?.let { NavigatingView(wide, it); return }

    val app = AmbientApp.instance
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val settings by app.prefs.settings.collectAsState()
    val here by app.location.location.collectAsState()
    val dest by NavModel.destination.collectAsState()
    val route by NavModel.route.collectAsState()
    val busy by NavModel.busy.collectAsState()
    val err by NavModel.error.collectAsState()

    var hasLoc by remember { mutableStateOf(app.location.hasPermission()) }
    val askLoc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLoc = app.location.hasPermission()
        if (hasLoc) app.location.start()
    }
    LaunchedEffect(Unit) { if (hasLoc) app.location.start() }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var recenter by remember { mutableIntStateOf(0) }
    var onMe by remember { mutableStateOf(false) }

    fun search(now: Boolean) {
        searchJob?.cancel()
        if (query.length < 3) { results = emptyList(); return }
        searchJob = scope.launch {
            if (!now) delay(700)            // Nominatim: como mucho una búsqueda por segundo
            searching = true
            results = runCatching { Net.search(query, here?.let { it.latitude to it.longitude }) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun go(p: Place) {
        NavModel.choose(p)
        results = emptyList()
        query = ""
        focus.clearFocus()
        onMe = false
        recenter++
    }

    // encuadrar la ruta solo cuando cambia, no cada vez que se entra en la pantalla
    val routeAtEntry = remember { route }
    LaunchedEffect(route) { if (route != null && route !== routeAtEntry) { onMe = false; recenter++ } }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp)).background(Color(0xFF0B0B0C))) {
        if (hasLoc) {
            OsmMap(MapScene(dest, route, recenter, centerOnMe = onMe), interactive = true, modifier = Modifier.fillMaxSize())
        } else {
            PermissionCard(
                "Permite la ubicación",
                "Para centrar el mapa en tu coche, calcular rutas y mostrar el tiempo exterior.",
                "Permitir",
                Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(24.dp),
            ) { askLoc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        }

        // ---- buscador y accesos rápidos ----
        Column(
            Modifier.padding(16.dp).then(if (wide) Modifier.width(420.dp) else Modifier.fillMaxWidth()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Glass(radius = 24.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Search, null, tint = Quiet, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Buscar destino", color = Faint, fontSize = 17.sp)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it; search(false) },
                            singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 17.sp),
                            cursorBrush = SolidColor(Red),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search(true) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) RoundIcon(Icons.Close, "Borrar", size = 40) { query = ""; results = emptyList() }
                }
            }

            if (results.isEmpty() && query.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickPlace("Casa", Icons.Home, settings.home) { go(it) }
                    QuickPlace("Trabajo", Icons.Work, settings.work) { go(it) }
                    settings.recent?.let { r -> if (r != settings.home && r != settings.work) QuickPlace(r.name, Icons.Nav, r) { go(it) } }
                }
            }

            AnimatedVisibility(results.isNotEmpty() || searching, enter = fadeIn(), exit = fadeOut()) {
                Glass(radius = 24.dp, padding = PaddingValues(6.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (searching && results.isEmpty()) Text("Buscando…", color = Quiet, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                        results.forEach { p ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { go(p) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Text(p.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.address, color = Quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // ---- volver a mi posición ----
        if (hasLoc) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(52.dp).clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).border(1.dp, GlassEdge, RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button, onClickLabel = "Centrar en mi posición") { onMe = true; recenter++ },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Locate, "Centrar en mi posición", tint = Ink, modifier = Modifier.size(22.dp)) }
        }

        // ---- ruta ----
        val d = dest
        if (d != null) {
            Glass(
                Modifier.align(Alignment.BottomStart).padding(16.dp).then(if (wide) Modifier.width(520.dp) else Modifier.fillMaxWidth()),
                radius = 26.dp, padding = PaddingValues(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.address, color = Quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RoundIcon(Icons.Close, "Quitar destino", size = 40) { NavModel.clear(); onMe = true; recenter++ }
                    }
                    val r = route
                    when {
                        busy -> Text("Calculando ruta…", color = Quiet, fontSize = 15.sp)
                        r != null -> {
                            val eta = Calendar.getInstance().apply { add(Calendar.SECOND, r.seconds.toInt()) }
                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                Stat(formatDuration(r.seconds), "tiempo")
                                Stat(formatDistance(r.meters), "distancia")
                                Stat("${two(eta.get(Calendar.HOUR_OF_DAY))}:${two(eta.get(Calendar.MINUTE))}", "llegada")
                            }
                        }
                        err != null -> Text(err ?: "", color = RedText, fontSize = 15.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val internal = settings.navApp == NavApp.INTERNAL
                        PillButton(
                            if (internal) "Iniciar navegación" else "Iniciar en ${settings.navApp.label}",
                            primary = true, icon = Icons.Nav, modifier = Modifier.weight(1f),
                            enabled = !internal || (r != null && !busy),
                        ) {
                            if (internal) r?.let { NavSession.start(context, d, it) }
                            else Net.startNavigation(context, settings.navApp, d.lat, d.lon)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Guardar como Casa", icon = Icons.Home, modifier = Modifier.weight(1f)) {
                            app.prefs.update { it.copy(home = d.copy(name = "Casa")) }
                        }
                        PillButton("Como Trabajo", icon = Icons.Work, modifier = Modifier.weight(1f)) {
                            app.prefs.update { it.copy(work = d.copy(name = "Trabajo")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPlace(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, place: Place?, onGo: (Place) -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.height(44.dp).clip(shape).background(Color.Black.copy(alpha = 0.6f)).border(1.dp, GlassEdge, shape)
            .clickable(enabled = place != null, role = Role.Button) { place?.let(onGo) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (place != null) Ink else Faint, modifier = Modifier.size(16.dp))
        Text(if (place == null) "$label (sin guardar)" else label, color = if (place != null) Ink else Faint, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Quiet, fontSize = 12.sp)
    }
}

/** Navegación paso a paso: mapa que sigue al coche, maniobra arriba, datos y mandos abajo. */
@Composable
private fun NavigatingView(wide: Boolean, st: NavState) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var follow by remember { mutableStateOf(true) }
    var recenter by remember { mutableIntStateOf(0) }

    // pantalla siempre encendida mientras guía
    androidx.compose.runtime.DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // si mueves el mapa, a los 12 s vuelve a seguir al coche
    LaunchedEffect(follow) { if (!follow) { delay(12_000); follow = true } }

    val zoom = when {
        st.speedKmh < 30 -> 17.6
        st.speedKmh < 70 -> 16.8
        else -> 15.8
    } + if (st.distToStep < 200 && st.speedKmh < 70) 0.4 else 0.0

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp)).background(Color(0xFF0B0B0C))) {
        OsmMap(
            MapScene(
                destination = st.destination,
                route = Route(st.remainingPoints, st.remainingM, st.remainingS),
                recenter = recenter,
                nav = es.scala.ambient.ui.NavCamera(st.lat, st.lon, st.bearing, zoom, follow),
                onUserPan = { follow = false },
            ),
            interactive = true,
            modifier = Modifier.fillMaxSize(),
        )
        if (follow) VehicleArrow(Modifier.align(Alignment.Center))
        GuidanceOverlay(
            st = st, wide = wide, follow = follow,
            onRecenter = { follow = true },
            onOverview = { follow = false; recenter++ },
            onStop = { NavSession.stop(context) },
            onMute = { NavSession.toggleMute() },
        )
    }
}
