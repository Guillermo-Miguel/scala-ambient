package es.scala.ambient.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import es.scala.ambient.AmbientApp
import es.scala.ambient.AmbientState
import es.scala.ambient.Cmd
import es.scala.ambient.Effect
import es.scala.ambient.Link
import es.scala.ambient.PALETTE
import es.scala.ambient.Scene
import es.scala.ambient.Zone
import es.scala.ambient.ZoneGroup
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.PI

@Composable
fun AmbientScreen(wide: Boolean) {
    val ble = AmbientApp.instance.ble
    val state by ble.state.collectAsState()
    val link by ble.link.collectAsState()
    var group by remember { mutableStateOf(ZoneGroup.ALL) }

    val trailing = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!LocalCarMode.current) Text(if (state.on) "Encendida" else "Apagada", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
            Switch(
                checked = state.on,
                onCheckedChange = { ble.send(Cmd.power(it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.15f)),
            )
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ScreenHeader("Luz ambiente", trailing = trailing)
                Text(link.label, color = if (link == Link.CONNECTED) Ink else RedText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                Cabin(state, group, { group = it }, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
                Label(if (LocalCarMode.current) "Toca una zona para editarla" else "Toca el salpicadero o una puerta para editarla", Modifier.padding(start = 6.dp))
            }
            // en el coche el panel ocupa algo más de la mitad, y todo él se desliza
            val car = LocalCarMode.current
            Glass(
                (if (car) Modifier.weight(1.35f) else Modifier.width(480.dp)).fillMaxHeight(),
                padding = PaddingValues(if (car) 16.dp else 22.dp),
            ) {
                Column(Modifier.fillMaxSize().carDrag().verticalScroll(rememberScrollState())) { Controls(state, group) { group = it } }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().carDrag().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader("Luz", trailing = trailing)
            Text(link.label, color = if (link == Link.CONNECTED) Ink else RedText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            Cabin(state, group, { group = it }, Modifier.fillMaxWidth().height(420.dp))
            Glass(Modifier.fillMaxWidth()) { Controls(state, group) { group = it } }
        }
    }
}

// ---------- Vista previa de los efectos en el coche dibujado ----------

private fun hueShift(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
    return Color.hsv((hsv[0] + deg + 360f) % 360f, if (hsv[1] < 0.25f) 0.65f else hsv[1], 1f)
}

/** Intensidad de una zona según el efecto (misma idea que el firmware, por zonas). */
private fun previewLevel(effect: Effect, z: Zone, t: Float, sp: Float): Float {
    val zi = z.ordinal
    return when (effect) {
        Effect.FIXED, Effect.RAINBOW, Effect.AURORA -> 1f
        Effect.BREATHE -> 0.3f + 0.7f * (0.5f + 0.5f * cos((t * sp / 4.2f) * 2 * PI).toFloat())
        Effect.WAVE -> {
            val d = when (z) { Zone.DASH -> 0f; Zone.FL, Zone.FR -> 0.35f; else -> 0.7f }
            val k = ((t * sp / 2.2f - d / 2.2f) % 1f + 1f) % 1f
            0.25f + 0.75f * (0.5f - 0.5f * cos(k * 2 * PI).toFloat())
        }
        Effect.PULSE -> {
            val k = (t * sp / 1.5f) % 1f
            fun g(c: Float, w: Float) = exp(-((k - c) / w) * ((k - c) / w))
            0.22f + 0.78f * max(g(0.10f, 0.045f), 0.7f * g(0.30f, 0.05f))
        }
        Effect.COMET -> {
            // la luz pasa por las zonas en orden: salpicadero → delanteras → traseras
            val order = when (z) { Zone.DASH -> 0f; Zone.FL, Zone.FR -> 1f; else -> 2f }
            val head = (t * sp * 0.55f * 3f) % 3f
            var d = head - order; if (d < 0) d += 3f
            0.10f + 0.90f * exp(-d * 2.2f)
        }
        Effect.CANDLE -> {
            val o = zi * 1.7f
            val f = 0.5f * sin(t * sp * 3.1f + o) + 0.3f * sin(t * sp * 7.3f + o * 2) + 0.2f * sin(t * sp * 13.7f + o * 3)
            0.62f + 0.30f * f
        }
    }
}

private fun previewColor(effect: Effect, z: Zone, base: Color, t: Float, sp: Float): Color = when (effect) {
    Effect.RAINBOW -> Color.hsv(((t * sp * 28f / 256f * 360f) + z.ordinal * 56f) % 360f, 0.9f, 1f)
    Effect.AURORA -> lerp(base, hueShift(base, 68f), 0.5f + 0.4f * sin(t * sp * 1.2f + z.ordinal))
    else -> base
}

@Composable
private fun Cabin(state: AmbientState, group: ZoneGroup, onPick: (ZoneGroup) -> Unit, modifier: Modifier) {
    // reloj de animación en segundos
    // solo corre mientras hay un efecto animado encendido (si no, redibujaría sin parar para nada)
    val running = state.on && state.effect != Effect.FIXED
    val t by produceState(0f, running) {
        if (!running) return@produceState
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) withInfiniteAnimationFrameMillis { value = (it - start) / 1000f }
    }
    val sp = 0.4f + state.speed * 0.16f

    val bases = Zone.entries.map { z -> animateColorAsState(state.zone(z).rgb.rgb(), tween(600), label = "c$z").value }
    val targets = Zone.entries.map { z ->
        animateFloatAsState(if (state.on) state.zone(z).bright / 100f else 0f, tween(600), label = "l$z").value
    }
    val animate = state.on && state.effect != Effect.FIXED
    val colors = Zone.entries.map { z -> if (animate) previewColor(state.effect, z, bases[z.ordinal], t, sp) else bases[z.ordinal] }
    val levels = Zone.entries.map { z -> targets[z.ordinal] * (if (animate) previewLevel(state.effect, z, t, sp) else 1f) }
    val selected = if (group == ZoneGroup.ALL) emptySet() else Zone.entries.filter { group.mask and it.bit != 0 }.toSet()

    Box(modifier, contentAlignment = Alignment.Center) {
        CarTopView(
            colors = colors, levels = levels, selected = selected,
            onTap = { z ->
                val g = when (z) {
                    null -> ZoneGroup.ALL
                    Zone.DASH -> ZoneGroup.DASH
                    Zone.FL -> ZoneGroup.FL
                    Zone.FR -> ZoneGroup.FR
                    Zone.RL -> ZoneGroup.RL
                    Zone.RR -> ZoneGroup.RR
                }
                onPick(if (g == group) ZoneGroup.ALL else g)
            },
            modifier = Modifier.fillMaxHeight(),
        )
    }
}

// ---------- Panel de ajustes ----------

@Composable
private fun Controls(state: AmbientState, group: ZoneGroup, onGroup: (ZoneGroup) -> Unit) {
    val ble = AmbientApp.instance.ble
    val lead = state.zone(group.lead)
    val accent by animateColorAsState(lead.rgb.rgb(), tween(600), label = "acc")

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("Editando")
                Text(group.label, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            }
            Label(state.scene?.let { "Escena · ${it.label}" } ?: "Personalizado")
        }

        val chips = listOf(ZoneGroup.ALL, ZoneGroup.DASH, ZoneGroup.DOORS)
        val sel = when (group) { ZoneGroup.ALL -> 0; ZoneGroup.DASH -> 1; else -> 2 }
        Segmented(chips.map { it.label }, sel, Modifier.fillMaxWidth()) { onGroup(chips[it]) }

        // Escenas
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("Escenas")
            // en el coche, de dos en dos para que los nombres quepan
            Scene.entries.chunked(if (LocalCarMode.current) 2 else 4).forEach { sceneRow ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                sceneRow.forEach { s ->
                    val on = state.scene == s
                    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.035f), tween(350), label = "sc")
                    val edge by animateColorAsState(if (on) Red else Color.White.copy(alpha = 0.08f), tween(350), label = "sce")
                    Column(
                        Modifier.weight(1f).height(if (LocalCarMode.current) 72.dp else 88.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                            .border(1.dp, edge, RoundedCornerShape(20.dp))
                            .clickable(role = Role.Button) { ble.send(Cmd.scene(s)) }.padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        when (s) {
                            Scene.FIRMA -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Dot(Ink, 9); Dot(Red, 9)
                            }
                            Scene.CONFORT -> Icon(Icons.Sun, null, tint = Ink, modifier = Modifier.size(22.dp))
                            Scene.SPORT -> Icon(Icons.Bolt, null, tint = RedText, modifier = Modifier.size(22.dp))
                            Scene.NOCHE -> Icon(Icons.Moon, null, tint = Ink, modifier = Modifier.size(22.dp))
                        }
                        Text(s.label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            }
        }

        // Color
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row { Label("Color", Modifier.weight(1f)); Text(state.swatchName(group.lead), color = Ink, fontSize = 14.sp) }
            PALETTE.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { sw ->
                        val picked = sw.rgb == lead.rgb
                        val sc by animateFloatAsState(if (picked) 1.08f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "sw")
                        Box(
                            Modifier.size(44.dp).scale(sc).clip(CircleShape).background(sw.rgb.rgb())
                                .then(if (picked) Modifier.border(3.dp, Ink, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape))
                                .semantics { contentDescription = sw.name }
                                .clickable(role = Role.Button) { ble.send(Cmd.color(group.mask, sw.rgb)) }
                        )
                    }
                }
            }
        }

        // Intensidad
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Label("Intensidad", Modifier.weight(1f))
                Text(if (state.on) "${lead.bright} %" else "Apagada", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = lead.bright.toFloat(),
                onValueChange = { ble.sendBrightness(group.mask, it.toInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
            )
        }

        // Efecto
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { Label("Efecto", Modifier.weight(1f)); Text(state.effect.label, color = Ink, fontSize = 14.sp) }
            Effect.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { e ->
                        val on = state.effect == e
                        val bg by animateColorAsState(if (on) Ink else Color.White.copy(alpha = 0.05f), tween(300), label = "fx")
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(16.dp)).background(bg)
                                .border(1.dp, if (on) Ink else GlassEdge, RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) { ble.send(Cmd.effect(e)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e.label, color = if (on) Ground else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                    }
                }
            }
            AnimatedVisibility(state.effect.animated, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Label("Velocidad", Modifier.weight(1f))
                        Text(
                            when { state.speed <= 3 -> "Lenta"; state.speed <= 7 -> "Media"; else -> "Rápida" },
                            color = Ink, fontSize = 14.sp,
                        )
                    }
                    var draft by remember(state.speed) { mutableFloatStateOf(state.speed.toFloat()) }
                    Slider(
                        value = draft,
                        onValueChange = { draft = it },
                        onValueChangeFinished = { ble.send(Cmd.speed(draft.toInt())) },
                        valueRange = 1f..10f, steps = 8,
                        colors = SliderDefaults.colors(thumbColor = Ink, activeTrackColor = Red, inactiveTrackColor = Color.White.copy(alpha = 0.12f)),
                    )
                }
            }
        }

        // Opciones
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bienvenida al abrir la puerta", color = Ink, fontSize = 15.sp)
                Label("Las luces se encienden en secuencia al abrir el coche")
            }
            Switch(
                checked = state.welcomeOnDoor,
                onCheckedChange = { ble.send(Cmd.welcomeOnDoor(it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.15f)),
            )
        }
    }
}
