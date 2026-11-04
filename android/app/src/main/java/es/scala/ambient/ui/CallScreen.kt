package es.scala.ambient.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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

/** Pantalla de llamada, dentro de la app y con el mismo estilo de cristal. */
@Composable
fun CallScreen(ui: CallUi) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.phase) { while (ui.phase == CallPhase.ACTIVE) { delay(1000); tick++ } }
    var keypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val status = when (ui.phase) {
        CallPhase.RINGING -> "Llamada entrante"
        CallPhase.DIALING -> "Llamando…"
        CallPhase.HOLDING -> "En espera"
        CallPhase.ENDED -> "Llamada finalizada"
        CallPhase.PICK_SIM -> "Elige la línea"
        CallPhase.ACTIVE -> if (tick < 0 || ui.connectedAt == 0L) "En llamada"
        else formatMs(SystemClock.elapsedRealtime() - ui.connectedAt)
    }

    // pulso suave alrededor del avatar mientras suena o marca
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "ring")
    val waiting = ui.phase == CallPhase.RINGING || ui.phase == CallPhase.DIALING

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))
            // tapa lo de debajo: los toques no pasan a la app
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogo(12.dp)
                LightMark()
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                Box(
                    Modifier.size(118.dp).scale(if (waiting) ring else 1f).clip(CircleShape)
                        .background(Red.copy(alpha = if (waiting) 0.18f else 0f))
                )
                Box(
                    Modifier.size(104.dp).clip(CircleShape).background(Color(0xFF1C1C20)).border(1.dp, GlassEdge, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.title.take(1).uppercase(), color = Ink, fontSize = 42.sp, fontWeight = FontWeight.Light)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ui.title, color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (ui.name != null) Text(ui.number, color = Quiet, fontSize = 16.sp)
                Text(status, color = if (ui.phase == CallPhase.ENDED) RedText else Quiet, fontSize = 17.sp)
                if (typed.isNotEmpty()) Text(typed, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }

            when (ui.phase) {
                CallPhase.PICK_SIM -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ui.sims.forEach { s -> PillButton(s.label, primary = true) { CallManager.chooseSim(s) } }
                }

                CallPhase.RINGING -> if (ui.incoming) {
                    Row(horizontalArrangement = Arrangement.spacedBy(64.dp), modifier = Modifier.padding(top = 24.dp)) {
                        BigCallButton(Icons.Phone, "Rechazar", Red, rotate = 135f) { CallManager.reject() }
                        BigCallButton(Icons.Phone, "Contestar", Ink, iconTint = Ground) { CallManager.answer() }
                    }
                }

                CallPhase.ENDED -> Spacer(Modifier.height(96.dp))

                else -> {
                    AnimatedVisibility(keypad, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            listOf("123", "456", "789", "*0#").forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    row.forEach { k ->
                                        Box(
                                            Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
                                                .clickable(role = Role.Button, onClickLabel = k.toString()) { typed += k; CallManager.tone(k) },
                                            contentAlignment = Alignment.Center,
                                        ) { Text(k.toString(), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Light) }
                                    }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        ToggleCallButton(if (ui.muted) Icons.MicOff else Icons.Mic, "Silenciar", ui.muted) { CallManager.toggleMute() }
                        ToggleCallButton(Icons.Dialpad, "Teclado", keypad) { keypad = !keypad }
                        ToggleCallButton(Icons.Speaker, "Altavoz", ui.speaker) { CallManager.toggleSpeaker() }
                    }
                    Spacer(Modifier.height(4.dp))
                    BigCallButton(Icons.Phone, "Colgar", Red, rotate = 135f) { CallManager.hangUp() }
                }
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
