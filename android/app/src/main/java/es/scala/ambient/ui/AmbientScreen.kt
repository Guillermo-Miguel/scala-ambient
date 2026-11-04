package es.scala.ambient.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.AmbientState
import es.scala.ambient.Cmd
import es.scala.ambient.Effect
import es.scala.ambient.Link
import es.scala.ambient.PALETTE
import es.scala.ambient.Scene
import es.scala.ambient.Zone
import es.scala.ambient.ZoneGroup

@Composable
fun AmbientScreen(wide: Boolean) {
    val ble = AmbientApp.instance.ble
    val state by ble.state.collectAsState()
    val link by ble.link.collectAsState()
    var group by remember { mutableStateOf(ZoneGroup.ALL) }

    val trailing = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.on) "Encendida" else "Apagada", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(end = 10.dp))
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
                EffectRow(state)
            }
            Glass(Modifier.width(470.dp).fillMaxHeight()) {
                Column(Modifier.verticalScroll(rememberScrollState())) { Controls(state, group) { group = it } }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScreenHeader("Luz", trailing = trailing)
            Text(link.label, color = if (link == Link.CONNECTED) Ink else RedText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            Cabin(state, group, { group = it }, Modifier.fillMaxWidth().height(420.dp))
            EffectRow(state)
            Glass(Modifier.fillMaxWidth()) { Controls(state, group) { group = it } }
        }
    }
}

@Composable
private fun Cabin(state: AmbientState, group: ZoneGroup, onPick: (ZoneGroup) -> Unit, modifier: Modifier) {
    val pulse = rememberInfiniteTransition(label = "fx")
    val breathe by pulse.animateFloat(0.3f, 1f, infiniteRepeatable(tween(2100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val wave by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "w")

    val colors = Zone.entries.map { z -> animateColorAsState(state.zone(z).rgb.rgb(), tween(600), label = "c$z").value }
    val levels = Zone.entries.map { z ->
        val target = if (state.on) state.zone(z).bright / 100f else 0f
        val base = animateFloatAsState(target, tween(600), label = "l$z").value
        base * when (state.effect) {
            Effect.FIXED -> 1f
            Effect.BREATHE -> breathe
            Effect.WAVE -> {
                val d = when (z) { Zone.DASH -> 0f; Zone.FL, Zone.FR -> 0.16f; else -> 0.32f }
                val t = (wave - d + 1f) % 1f
                0.25f + 0.75f * (0.5f - 0.5f * kotlin.math.cos(t * 2 * Math.PI).toFloat())
            }
        }
    }
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

@Composable
private fun EffectRow(state: AmbientState) {
    val ble = AmbientApp.instance.ble
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("Toca el salpicadero o una puerta", Modifier.weight(1f))
        Segmented(Effect.entries.map { it.label }, state.effect.ordinal) { ble.send(Cmd.effect(Effect.entries[it])) }
    }
}

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

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("Escenas")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Scene.entries.forEach { s ->
                    val on = state.scene == s
                    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.035f), tween(350), label = "sc")
                    val edge by animateColorAsState(if (on) Red else Color.White.copy(alpha = 0.08f), tween(350), label = "sce")
                    Column(
                        Modifier.weight(1f).height(88.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                            .border(1.dp, edge, RoundedCornerShape(20.dp))
                            .clickable(role = Role.Button) { ble.send(Cmd.scene(s)) }.padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        when (s) {
                            Scene.FIRMA -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.size(18.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Ink))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Red))
                            }
                            Scene.CONFORT -> Icon(Icons.Sun, null, tint = Ink, modifier = Modifier.size(22.dp))
                            Scene.SPORT -> Icon(Icons.Bolt, null, tint = RedText, modifier = Modifier.size(22.dp))
                            Scene.NOCHE -> Icon(Icons.Moon, null, tint = Ink, modifier = Modifier.size(22.dp))
                        }
                        Text(s.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        PillButton("Probar bienvenida", modifier = Modifier.fillMaxWidth()) { ble.send(Cmd.WELCOME) }
    }
}
