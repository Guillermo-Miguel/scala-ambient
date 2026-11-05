package es.scala.ambient.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.scala.ambient.AmbientApp
import es.scala.ambient.Zone
import es.scala.ambient.nav.NavModel
import es.scala.ambient.telecom.CallManager

enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Inicio", Icons.Home),
    NAV("Navegación", Icons.Nav),
    MUSIC("Música", Icons.Music),
    PHONE("Teléfono", Icons.Phone),
    LIGHT("Luz ambiente", Icons.Light),
    SETTINGS("Ajustes", Icons.Settings),
}

@Composable
fun AppRoot() {
    val app = AmbientApp.instance
    val settings by app.prefs.settings.collectAsState()
    val ambient by app.ble.state.collectAsState()
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }
    var welcomeDone by rememberSaveable { mutableStateOf(!settings.showWelcome) }

    LaunchedEffect(Unit) { NavModel.refreshWeather() }

    val call by CallManager.ui.collectAsState()

    // Durante una llamada, mantener la pantalla en horizontal si así la usas
    // (al entrar una llamada con la pantalla bloqueada, Android tiende a girarla a vertical).
    val activity = LocalContext.current as? Activity
    val config = LocalConfiguration.current
    SideEffect {
        if (call == null) LastOrientation.landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    }
    val inCall = call != null
    DisposableEffect(inCall) {
        if (inCall && activity != null) {
            val landscape = LastOrientation.landscape ?: (config.smallestScreenWidthDp >= 600)
            if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val dialRequest by CallManager.dialRequest.collectAsState()
    LaunchedEffect(dialRequest) { if (dialRequest != null) { dest = Dest.PHONE; welcomeDone = true } }

    // Reflejo de la luz ambiente detrás del cristal
    val dashColor by animateColorAsState(ambient.zone(Zone.DASH).rgb.rgb(), tween(900), label = "bgA")
    val doorColor by animateColorAsState(ambient.zone(Zone.FL).rgb.rgb(), tween(900), label = "bgB")
    val glow by animateFloatAsState(if (ambient.on) 1f else 0f, tween(900), label = "bgOn")

    ScaledUi(settings.uiScale) {
        Box(Modifier.fillMaxSize().background(Ground)) {
            // Reflejo de la luz: degradados suaves a pantalla completa (sin bordes ni recortes)
            Box(
                Modifier.fillMaxSize().drawBehind {
                    if (glow <= 0f) return@drawBehind
                    val big = size.maxDimension
                    // salpicadero: arriba, hacia el centro
                    drawRect(
                        Brush.radialGradient(
                            0f to dashColor.copy(alpha = 0.16f * glow),
                            0.5f to dashColor.copy(alpha = 0.05f * glow),
                            1f to Color.Transparent,
                            center = Offset(size.width * 0.42f, -size.height * 0.15f),
                            radius = big * 0.75f,
                        )
                    )
                    // puertas: abajo a la derecha
                    drawRect(
                        Brush.radialGradient(
                            0f to doorColor.copy(alpha = 0.24f * glow),
                            0.5f to doorColor.copy(alpha = 0.08f * glow),
                            1f to Color.Transparent,
                            center = Offset(size.width * 1.0f, size.height * 1.05f),
                            radius = big * 0.8f,
                        )
                    )
                }
            )

            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                // horizontal (coche, tablet) → menú lateral; vertical (móvil) → barra abajo
            val wide = maxWidth > maxHeight || maxWidth >= 720.dp
                val content: @Composable (Modifier) -> Unit = { m ->
                    AnimatedContent(
                        targetState = dest,
                        transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.985f)) togetherWith fadeOut(tween(200)) },
                        modifier = m,
                        label = "dest",
                    ) { d ->
                        when (d) {
                            Dest.HOME -> HomeScreen(wide, onOpen = { dest = it })
                            Dest.NAV -> NavScreen(wide)
                            Dest.MUSIC -> MusicScreen(wide)
                            Dest.PHONE -> PhoneScreen(wide)
                            Dest.LIGHT -> AmbientScreen(wide)
                            Dest.SETTINGS -> SettingsScreen(wide, onReplayWelcome = { welcomeDone = false })
                        }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Rail(dest, vertical = true, modifier = Modifier.width(76.dp).fillMaxHeight()) { dest = it }
                        content(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        content(Modifier.weight(1f).fillMaxWidth())
                        Rail(dest, vertical = false, modifier = Modifier.fillMaxWidth().height(68.dp)) { dest = it }
                    }
                }
            }

            AnimatedVisibility(!welcomeDone && call == null, enter = fadeIn(tween(200)), exit = fadeOut(tween(700))) {
                WelcomeScreen(onDone = { welcomeDone = true })
            }

            // Llamada en curso o entrante: ocupa toda la pantalla, dentro de la app
            AnimatedVisibility(call != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(400))) {
                call?.let { CallScreen(it) }
            }
        }
    }
}

@Composable
private fun Rail(current: Dest, vertical: Boolean, modifier: Modifier, onPick: (Dest) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier.clip(shape).background(GlassFill).border(1.dp, GlassEdge, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (vertical) {
            Column(
                Modifier.fillMaxHeight().padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Dest.entries.forEach { d ->
                    if (d == Dest.SETTINGS) Spacer(Modifier.weight(1f))
                    RailButton(d, d == current) { onPick(d) }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dest.entries.forEach { d -> RailButton(d, d == current) { onPick(d) } }
            }
        }
    }
}

@Composable
private fun RailButton(d: Dest, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Color.White.copy(alpha = 0.12f) else Color.Transparent, tween(300), label = "rail")
    val fg by animateColorAsState(if (active) Ink else Quiet, tween(300), label = "railfg")
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(role = Role.Tab) { onClick() }
            .semantics { contentDescription = d.label; selected = active },
        contentAlignment = Alignment.Center,
    ) {
        Icon(d.icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        if (active) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(width = 14.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(Red))
        }
    }
}

/**
 * Escala toda la interfaz para que se vea igual de proporcionada en cualquier pantalla:
 * el diseño está pensado para unos 1200 × 660 en horizontal y 420 × 860 en vertical.
 * Una pantalla de 7" (800 × 480) se ve como el diseño, solo que más pequeña;
 * una de 10" o una tablet, más grande. `userScale` es el ajuste fino de Ajustes.
 */
@Composable
fun ScaledUi(userScale: Float, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val landscape = maxWidth > maxHeight
        val fit = if (landscape) minOf(maxWidth / 1200.dp, maxHeight / 660.dp)
        else minOf(maxWidth / 420.dp, maxHeight / 860.dp)
        val s = (fit.coerceIn(0.6f, 1.3f) * userScale).coerceIn(0.5f, 1.5f)
        val scaled = Density(base.density * s, base.fontScale.coerceIn(0.85f, 1.15f))
        CompositionLocalProvider(LocalDensity provides scaled) { content() }
    }
}

/** Última orientación con la que usabas la app (fuera de llamadas). */
private object LastOrientation {
    var landscape: Boolean? = null
}
