package es.scala.ambient.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.telecom.CallManager
import es.scala.ambient.telecom.CallPhase
import es.scala.ambient.telecom.CallUi
import kotlinx.coroutines.delay

/**
 * Pantalla de llamada, dentro de la app y con el mismo estilo de cristal.
 * En horizontal (pantalla del coche, tablet): contacto a la izquierda y mandos a la derecha.
 * En vertical (móvil): todo en una columna.
 */
@Composable
fun CallScreen(ui: CallUi) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.phase) { while (ui.phase == CallPhase.ACTIVE) { delay(1000); tick++ } }
    var keypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val status = when (ui.phase) {
        CallPhase.RINGING -> if (ui.external) "Suena el teléfono" else "Llamada entrante"
        CallPhase.DIALING -> "Llamando…"
        CallPhase.HOLDING -> "En espera"
        CallPhase.ENDED -> "Llamada finalizada"
        CallPhase.PICK_SIM -> "Elige la línea"
        CallPhase.ACTIVE -> if (tick < 0 || ui.connectedAt == 0L) (if (ui.external && !ui.incoming) "Llamando · en llamada" else "En llamada")
        else formatMs(SystemClock.elapsedRealtime() - ui.connectedAt)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f))
            // tapa lo de debajo: los toques no pasan a la app
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 40.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                // contacto
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandLogo(12.dp)
                        LightMark()
                    }
                    Spacer(Modifier.height(28.dp))
                    Contact(ui, status, typed, avatar = 150.dp)
                }
                // separador fino
                Box(Modifier.width(1.dp).fillMaxHeight(0.7f).background(GlassEdge))
                // mandos
                Column(
                    Modifier.weight(1f).fillMaxHeight().carDrag().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Controls(ui, keypad, { keypad = !keypad }) { k -> typed += k; CallManager.tone(k) }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).carDrag().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandLogo(12.dp)
                    LightMark()
                }
                Contact(ui, status, typed, avatar = 150.dp)
                Controls(ui, keypad, { keypad = !keypad }) { k -> typed += k; CallManager.tone(k) }
            }
        }
    }
}

/** Avatar con pulso, nombre, número y estado. */
@Composable
private fun Contact(ui: CallUi, status: String, typed: String, avatar: androidx.compose.ui.unit.Dp) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring")
    val waiting = ui.phase == CallPhase.RINGING || ui.phase == CallPhase.DIALING

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(avatar)) {
            Box(
                Modifier.size(avatar * 0.8f).scale(if (waiting) ring else 1f).clip(CircleShape)
                    .background(Red.copy(alpha = if (waiting) 0.18f else 0f))
            )
            Box(
                Modifier.size(avatar * 0.7f).clip(CircleShape).background(Color(0xFF1C1C20)).border(1.dp, GlassEdge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(ui.title.take(1).uppercase(), color = Ink, fontSize = 42.sp, fontWeight = FontWeight.Light)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (ui.name != null && ui.number.isNotBlank()) Text(ui.number, color = Quiet, fontSize = 16.sp)
            Text(status, color = if (ui.phase == CallPhase.ENDED) RedText else Quiet, fontSize = 18.sp)
            ui.hint?.let { Text(it, color = RedText, fontSize = 14.sp, textAlign = TextAlign.Center) }
            if (typed.isNotEmpty()) Text(typed, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1)
        }
    }
}

/** Botones según el momento de la llamada. */
@Composable
private fun Controls(ui: CallUi, keypad: Boolean, onKeypad: () -> Unit, onKey: (Char) -> Unit) {
    when (ui.phase) {
        CallPhase.PICK_SIM -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.sims.forEach { s -> PillButton(s.label, primary = true) { CallManager.chooseSim(s) } }
        }

        CallPhase.RINGING -> if (ui.incoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                BigCallButton(Icons.Phone, "Rechazar", Red, rotate = 135f) { CallManager.reject() }
                BigCallButton(Icons.Phone, "Contestar", Ink, iconTint = Ground) { CallManager.answer() }
            }
        }

        CallPhase.ENDED -> Spacer(Modifier.height(96.dp))

        // llamada que lleva el sistema (Android Auto): solo colgar
        else -> if (ui.external) {
            BigCallButton(Icons.Phone, "Colgar", Red, rotate = 135f) { CallManager.hangUp() }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {

            // el teclado ocupa el sitio de los botones para que quepa en pantallas bajas
            AnimatedContent(keypad, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "keypad") { showPad ->
                if (showPad) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        listOf("123", "456", "789", "*0#").forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                row.forEach { k ->
                                    Box(
                                        Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
                                            .clickable(role = Role.Button, onClickLabel = k.toString()) { onKey(k) },
                                        contentAlignment = Alignment.Center,
                                    ) { Text(k.toString(), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Light) }
                                }
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        ToggleCallButton(if (ui.muted) Icons.MicOff else Icons.Mic, "Silenciar", ui.muted) { CallManager.toggleMute() }
                        ToggleCallButton(Icons.Dialpad, "Teclado", false, onKeypad)
                        ToggleCallButton(Icons.Speaker, "Altavoz", ui.speaker) { CallManager.toggleSpeaker() }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (keypad) ToggleCallButton(Icons.Dialpad, "Ocultar", true, onKeypad)
                BigCallButton(Icons.Phone, "Colgar", Red, rotate = 135f) { CallManager.hangUp() }
                if (keypad) ToggleCallButton(Icons.Speaker, "Altavoz", ui.speaker) { CallManager.toggleSpeaker() }
            }
        }
    }
}

@Composable
private fun ToggleCallButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (on) Ink else Color.White.copy(alpha = 0.08f), tween(250), label = "tg")
    val fg by animateColorAsState(if (on) Ground else Ink, tween(250), label = "tgf")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(bg).border(1.dp, GlassEdge, CircleShape)
                .clickable(role = Role.Switch, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = fg, modifier = Modifier.size(26.dp)) }
        Text(label, color = Quiet, fontSize = 13.sp)
    }
}

@Composable
private fun BigCallButton(icon: ImageVector, label: String, color: Color, rotate: Float = 0f, iconTint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(78.dp).clip(CircleShape).background(color)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = iconTint, modifier = Modifier.size(30.dp).rotate(rotate)) }
        Text(label, color = Quiet, fontSize = 13.sp)
    }
}
