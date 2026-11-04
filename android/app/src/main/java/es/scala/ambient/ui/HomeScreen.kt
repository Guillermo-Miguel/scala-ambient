package es.scala.ambient.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.Zone
import es.scala.ambient.media.NowPlaying
import es.scala.ambient.NavApp
import es.scala.ambient.nav.NavModel
import es.scala.ambient.nav.NavSession
import es.scala.ambient.nav.Route
import es.scala.ambient.nav.shortDistance
import es.scala.ambient.nav.Net
import es.scala.ambient.nav.formatDistance
import es.scala.ambient.nav.formatDuration
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun HomeScreen(wide: Boolean, onOpen: (Dest) -> Unit) {
    val settings by AmbientApp.instance.prefs.settings.collectAsState()
    val now = rememberNow()
    val h = now.get(Calendar.HOUR_OF_DAY)
    val hello = when { h < 6 -> "Buenas noches"; h < 13 -> "Buenos días"; h < 21 -> "Buenas tardes"; else -> "Buenas noches" }
    val greeting = if (settings.driverName.isBlank()) hello else "$hello, ${settings.driverName}"
    val date = remember(now.get(Calendar.DAY_OF_YEAR)) {
        SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES")).format(now.time).replaceFirstChar { it.uppercase() }
    }

    val header = @Composable {
        ScreenHeader(greeting) {
            Column(horizontalAlignment = Alignment.End) {
                Text(date, color = Quiet, fontSize = 14.sp)
                Text("${two(h)}:${two(now.get(Calendar.MINUTE))}", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Light)
            }
        }
    }

    var weatherOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            header()
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NavTile(Modifier.weight(1.25f).fillMaxHeight(), onOpen)
                Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MediaTile(Modifier.weight(1f).fillMaxWidth(), onOpen)
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        AmbientTile(Modifier.weight(1f).fillMaxHeight(), onOpen)
                        WeatherTile(Modifier.weight(1f).fillMaxHeight()) { weatherOpen = true }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            MediaTile(Modifier.fillMaxWidth().height(220.dp), onOpen)
            NavTile(Modifier.fillMaxWidth().height(300.dp), onOpen)
            Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AmbientTile(Modifier.weight(1f).fillMaxHeight(), onOpen)
                WeatherTile(Modifier.weight(1f).fillMaxHeight()) { weatherOpen = true }
            }
        }
    }

        AnimatedVisibility(
            weatherOpen,
            enter = fadeIn(tween(250)) + scaleIn(tween(320), initialScale = 0.94f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.96f),
        ) {
            WeatherPanel(wide) { weatherOpen = false }
        }
    }
}

// ---------- Navegación ----------
@Composable
private fun NavTile(modifier: Modifier, onOpen: (Dest) -> Unit) {
    val context = LocalContext.current
    val settings by AmbientApp.instance.prefs.settings.collectAsState()
    val dest by NavModel.destination.collectAsState()
    val route by NavModel.route.collectAsState()
    val busy by NavModel.busy.collectAsState()
    val err by NavModel.error.collectAsState()
    val shape = RoundedCornerShape(30.dp)
    val guiding by NavSession.state.collectAsState()

    // guiando: la próxima maniobra en grande; toca para ir a la navegación
    guiding?.let { g ->
        Box(modifier.clip(shape).background(Color(0xFF0B0B0C)).clickable(role = Role.Button) { onOpen(Dest.NAV) }) {
            OsmMap(
                MapScene(g.destination, Route(g.remainingPoints, g.remainingM, g.remainingS), 0, nav = NavCamera(g.lat, g.lon, g.bearing, 16.0, true)),
                interactive = false, modifier = Modifier.fillMaxSize(),
            )
            VehicleArrow(Modifier.align(Alignment.Center))
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Abrir navegación") { onOpen(Dest.NAV) })
            Glass(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp), radius = 22.dp, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ManeuverIcon(g.step, Modifier.size(46.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (g.arrived) "Has llegado" else shortDistance(g.distToStep), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        Text(g.step?.text ?: "", color = Quiet, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val eta = Calendar.getInstance().apply { add(Calendar.SECOND, g.remainingS.toInt()) }
            Glass(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp), radius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Llegada ${two(eta.get(Calendar.HOUR_OF_DAY))}:${two(eta.get(Calendar.MINUTE))} · ${formatDuration(g.remainingS)} · ${formatDistance(g.remainingM)}",
                    color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }

    Box(modifier.clip(shape).background(Color(0xFF0B0B0C))) {
        OsmMap(MapScene(dest, route, recenter = (route?.points?.size ?: 0) + (dest?.hashCode() ?: 0)), interactive = false, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = "Abrir navegación") { onOpen(Dest.NAV) })

        Row(
            Modifier.padding(16.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Nav, null, tint = Ink, modifier = Modifier.size(16.dp))
            Text("Navegación", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        val target = dest ?: settings.home ?: settings.recent
        Glass(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),
            radius = 24.dp, padding = PaddingValues(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(target?.name ?: "¿A dónde vamos?", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        target == null -> "Toca para buscar un destino"
                        dest == null -> "Toca Ir para calcular la ruta"
                        busy -> "Calculando ruta…"
                        route != null -> {
                            val eta = Calendar.getInstance().apply { add(Calendar.SECOND, route!!.seconds.toInt()) }
                            "${formatDuration(route!!.seconds)} · ${formatDistance(route!!.meters)} · llegada ${two(eta.get(Calendar.HOUR_OF_DAY))}:${two(eta.get(Calendar.MINUTE))}"
                        }
                        else -> err ?: ""
                    }
                    Text(sub, color = Quiet, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                when {
                    target == null -> PillButton("Buscar", icon = Icons.Search) { onOpen(Dest.NAV) }
                    dest == null -> PillButton("Ir", primary = true) { NavModel.choose(target) }
                    else -> PillButton("Iniciar", primary = true, enabled = settings.navApp != NavApp.INTERNAL || route != null) {
                        if (settings.navApp == NavApp.INTERNAL) {
                            route?.let { NavSession.start(context, target, it); onOpen(Dest.NAV) }
                        } else Net.startNavigation(context, settings.navApp, target.lat, target.lon)
                    }
                }
            }
        }
    }
}

// ---------- Música ----------
@Composable
private fun MediaTile(modifier: Modifier, onOpen: (Dest) -> Unit) {
    val media = AmbientApp.instance.media
    val np by media.now.collectAsState()
    val access by media.hasAccess.collectAsState()
    val context = LocalContext.current

    Glass(modifier, onClick = { onOpen(Dest.MUSIC) }) {
        if (!access) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Música")
                Text("Ver lo que suena en el móvil", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Toca para dar el acceso a notificaciones (te explico cómo).", color = Quiet, fontSize = 14.sp)
            }
            return@Glass
        }
        if (np == null) {
            // no suena nada: reanudar y accesos directos a las apps de música
            val apps = remember { media.installedMusicApps().take(5) }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoundIcon(Icons.Play, "Reanudar", size = 54, filled = true) { media.resumeLast() }
                    Column(Modifier.weight(1f)) {
                        Text("No suena nada", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Reanuda o elige una app", color = Quiet, fontSize = 14.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    apps.forEach { app ->
                        val img = remember(app.packageName) { app.icon.asImageBitmap() }
                        Image(
                            img, app.label,
                            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = "Abrir ${app.label}") { media.openApp(app.packageName) },
                        )
                    }
                }
            }
            return@Glass
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            AlbumArt(np, Modifier.fillMaxHeight().aspectRatio(1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(when { np == null -> "Nada sonando"; np!!.playing -> "Reproduciendo · ${np!!.appName}"; else -> "En pausa · ${np!!.appName}" })
                    Text(np?.title?.ifBlank { null } ?: "Pon música en el móvil", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(np?.artist ?: "", color = Quiet, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressLine(np)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIcon(Icons.Prev, "Anterior", enabled = np?.canSkipPrev == true) { media.previous() }
                    RoundIcon(if (np?.playing == true) Icons.Pause else Icons.Play, if (np?.playing == true) "Pausar" else "Reproducir", size = 58, filled = true, enabled = np != null) { media.playPause() }
                    RoundIcon(Icons.Next, "Siguiente", enabled = np?.canSkipNext == true) { media.next() }
                }
            }
        }
    }
}

@Composable
fun AlbumArt(np: NowPlaying?, modifier: Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).background(Color(0xFF151518)), contentAlignment = Alignment.Center) {
        val art = np?.art
        if (art != null) {
            val img = remember(art) { art.asImageBitmap() }
            Image(img, contentDescription = "Carátula", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Music, null, tint = Faint, modifier = Modifier.size(40.dp))
        }
    }
}

/** Barra de progreso que avanza sola mientras suena. */
@Composable
fun ProgressLine(np: NowPlaying?, modifier: Modifier = Modifier) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np?.playing) { while (np?.playing == true) { delay(500); tick++ } }
    // leer `tick` hace que se recalcule cada medio segundo
    val frac = if (tick < 0L || np == null || np.durationMs <= 0) 0f else (np.positionNow().toFloat() / np.durationMs).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Ink))
    }
}

// ---------- Luz ambiente ----------
@Composable
private fun AmbientTile(modifier: Modifier, onOpen: (Dest) -> Unit) {
    val ble = AmbientApp.instance.ble
    val st by ble.state.collectAsState()
    val link by ble.link.collectAsState()
    Glass(modifier, onClick = { onOpen(Dest.LIGHT) }, padding = PaddingValues(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // el coche con sus luces, en pequeño
            CarTopView(
                colors = Zone.entries.map { st.zone(it).rgb.rgb() },
                levels = Zone.entries.map { if (st.on) st.zone(it).bright / 100f else 0f },
                selected = emptySet(), onTap = null,
                modifier = Modifier.fillMaxHeight(),
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Luz ambiente", Modifier.weight(1f))
                    Icon(Icons.Chevron, null, tint = Quiet, modifier = Modifier.size(18.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (!st.on) "Apagada" else st.scene?.label ?: "Personalizado",
                        color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (st.on) Text("${st.effect.label} · ${st.zone(Zone.DASH).bright} %", color = Quiet, fontSize = 14.sp, maxLines = 1)
                }
                Text(link.label, color = if (link == es.scala.ambient.Link.CONNECTED) Quiet else Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------- Tiempo ----------
@Composable
private fun WeatherTile(modifier: Modifier, onExpand: () -> Unit) {
    val w by NavModel.weather.collectAsState()
    Glass(modifier, onClick = onExpand) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(w?.place ?: "Exterior", Modifier.weight(1f))
                Icon(weatherIcon(w?.code ?: 0, w?.isDay ?: true), null, tint = Ink, modifier = Modifier.size(22.dp))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(w?.let { deg(it.tempC) } ?: "—", color = Ink, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text(w?.label ?: "Esperando posición", color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    w?.today?.let { Text("↑ ${deg(it.maxC)}  ↓ ${deg(it.minC)}", color = Quiet, fontSize = 13.sp, maxLines = 1) }
                }
            }
            if (w != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MiniStat(Icons.Drop, "${w!!.rainProbNow} %")
                    MiniStat(Icons.Wind, "${w!!.windKmh.roundToInt()} km/h")
                    MiniStat(Icons.Humidity, "${w!!.humidity} %")
                }
            }
        }
    }
}

@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = Quiet, modifier = Modifier.size(14.dp))
        Text(text, color = Quiet, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))
