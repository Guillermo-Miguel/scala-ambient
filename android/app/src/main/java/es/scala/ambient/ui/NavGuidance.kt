package es.scala.ambient.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.nav.NavState
import es.scala.ambient.nav.Step
import es.scala.ambient.nav.formatDistance
import es.scala.ambient.nav.formatDuration
import es.scala.ambient.nav.shortDistance
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Ángulo de la flecha (grados, 0 = recto, negativo = izquierda). null = dibujo especial. */
private fun arrowAngle(s: Step): Float? {
    val m = s.modifier ?: "straight"
    if (s.type in setOf("arrive", "roundabout", "rotary") || m == "uturn") return null
    return when (m) {
        "sharp left" -> -135f
        "left" -> -90f
        "slight left" -> -40f
        "slight right" -> 40f
        "right" -> 90f
        "sharp right" -> 135f
        else -> when (s.type) {
            "fork", "on ramp", "off ramp", "merge" -> 0f
            else -> 0f
        }
    }
}

/** Dibujo de la maniobra: flecha, rotonda, cambio de sentido o llegada. */
@Composable
fun ManeuverIcon(step: Step?, modifier: Modifier = Modifier, color: Color = Ink) {
    Canvas(modifier) {
        val w = size.width
        val stroke = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun head(tip: Offset, angleDeg: Float) {
            val a = Math.toRadians(angleDeg.toDouble())
            val l = w * 0.17f
            val p = Path().apply {
                moveTo(tip.x - l * cos(a - 0.6).toFloat(), tip.y - l * sin(a - 0.6).toFloat())
                lineTo(tip.x, tip.y)
                lineTo(tip.x - l * cos(a + 0.6).toFloat(), tip.y - l * sin(a + 0.6).toFloat())
            }
            drawPath(p, color, style = stroke)
        }
        val s = step
        when {
            s == null -> {}
            s.type == "arrive" -> {
                drawCircle(color, w * 0.24f, Offset(w / 2, w * 0.42f), style = stroke)
                drawCircle(Red, w * 0.09f, Offset(w / 2, w * 0.42f))
                drawLine(color, Offset(w / 2, w * 0.66f), Offset(w / 2, w * 0.9f), stroke.width, StrokeCap.Round)
            }
            s.type == "roundabout" || s.type == "rotary" -> {
                val c = Offset(w / 2, w * 0.45f)
                drawCircle(color, w * 0.2f, c, style = stroke)
                drawLine(color, Offset(w / 2, w * 0.92f), Offset(w / 2, c.y + w * 0.2f), stroke.width, StrokeCap.Round)
                // salida: cuanto mayor el número, más a la izquierda
                val exitAng = when (s.exit ?: 2) { 1 -> -10f; 2 -> -90f; 3 -> -170f; else -> -200f }
                val a = Math.toRadians(exitAng.toDouble())
                val from = Offset(c.x + w * 0.2f * cos(a).toFloat(), c.y + w * 0.2f * sin(a).toFloat())
                val tip = Offset(c.x + w * 0.4f * cos(a).toFloat(), c.y + w * 0.4f * sin(a).toFloat())
                drawLine(color, from, tip, stroke.width, StrokeCap.Round)
                head(tip, exitAng)
            }
            s.modifier == "uturn" -> {
                val p = Path().apply {
                    moveTo(w * 0.62f, w * 0.9f); lineTo(w * 0.62f, w * 0.4f)
                    cubicTo(w * 0.62f, w * 0.12f, w * 0.3f, w * 0.12f, w * 0.3f, w * 0.4f)
                    lineTo(w * 0.3f, w * 0.62f)
                }
                drawPath(p, color, style = stroke)
                head(Offset(w * 0.3f, w * 0.66f), 90f)
            }
            else -> {
                val ang = arrowAngle(s) ?: 0f
                val base = Offset(w / 2, w * 0.92f)
                val knee = Offset(w / 2, if (ang == 0f) w * 0.5f else w * 0.55f)
                val a = Math.toRadians((ang - 90).toDouble())
                val len = if (ang == 0f) w * 0.42f else w * 0.36f
                val tip = Offset(knee.x + len * cos(a).toFloat(), knee.y + len * sin(a).toFloat())
                val p = Path().apply { moveTo(base.x, base.y); lineTo(knee.x, knee.y); lineTo(tip.x, tip.y) }
                drawPath(p, color, style = stroke)
                head(tip, ang - 90)
            }
        }
    }
}

/** Interfaz de guía encima del mapa. */
@Composable
fun BoxScope.GuidanceOverlay(
    st: NavState,
    wide: Boolean,
    follow: Boolean,
    onRecenter: () -> Unit,
    onOverview: () -> Unit,
    onStop: () -> Unit,
    onMute: () -> Unit,
) {
    // ---- maniobra (arriba a la izquierda) ----
    val banner = RoundedCornerShape(26.dp)
    Column(
        Modifier.align(Alignment.TopStart).padding(14.dp).then(if (wide) Modifier.width(if (LocalCarMode.current) 380.dp else 440.dp) else Modifier.fillMaxWidth())
            .clip(banner).background(Color(0xF0101012)).border(1.dp, GlassEdge, banner),
    ) {
        AnimatedContent(
            targetState = Triple(st.step, st.rerouting, st.arrived),
            transitionSpec = { (fadeIn(tween(250)) + slideInVertically { -it / 4 }) togetherWith fadeOut(tween(150)) },
            label = "maneuver",
        ) { (step, rerouting, arrived) ->
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ManeuverIcon(if (rerouting) null else step, Modifier.size(68.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        arrived -> {
                            Text("Has llegado", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                            Text(st.destination.name, color = Quiet, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        rerouting -> Text("Recalculando…", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> {
                            Text(shortDistance(st.distToStep), color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                            Text(step?.text ?: "", color = Ink, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                        }
                    }
                }
            }
        }
        val next = st.nextStep
        if (!st.arrived && !st.rerouting && next != null && st.step?.type != "arrive") {
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Después", color = Quiet, fontSize = 13.sp)
                ManeuverIcon(next, Modifier.size(22.dp), Quiet)
                Text(next.text, color = Quiet, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // ---- volver a seguir al coche ----
    if (!follow && !st.arrived) {
        Row(
            Modifier.align(Alignment.CenterEnd).padding(16.dp).clip(RoundedCornerShape(24.dp)).background(Ink)
                .clickable(role = Role.Button, onClick = onRecenter).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Nav, null, tint = Ground, modifier = Modifier.size(18.dp))
            Text("Seguir", color = Ground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    // ---- barra inferior: llegada, tiempo, distancia, velocidad y mandos ----
    val eta = Calendar.getInstance().apply { add(Calendar.SECOND, st.remainingS.toInt()) }
    Glass(
        Modifier.align(Alignment.BottomCenter).padding(14.dp).fillMaxWidth(),
        radius = 26.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(2.dp, Ink.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${st.speedKmh}", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                    Text("km/h", color = Quiet, fontSize = 10.sp, lineHeight = 10.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (st.arrived) "En destino" else "Llegada ${two(eta.get(Calendar.HOUR_OF_DAY))}:${two(eta.get(Calendar.MINUTE))}",
                    color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatDuration(st.remainingS)} · ${formatDistance(st.remainingM)}" + (st.road.ifBlank { null }?.let { " · $it" } ?: ""),
                    color = Quiet, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RoundIcon(if (st.muted) Icons.MicOff else Icons.Speaker, if (st.muted) "Activar voz" else "Silenciar voz", size = 48, onClick = onMute)
            if (wide) RoundIcon(Icons.Search, "Vista general", size = 48, onClick = onOverview)
            Box(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Red)
                    .clickable(role = Role.Button, onClickLabel = "Finalizar navegación", onClick = onStop).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text(if (st.arrived) "Cerrar" else "Finalizar", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Flecha del coche, en el centro del mapa mientras se le sigue. */
@Composable
fun VehicleArrow(modifier: Modifier = Modifier) {
    Canvas(modifier.size(46.dp)) {
        val w = size.width
        drawCircle(Red.copy(alpha = 0.22f), w * 0.5f)
        val p = Path().apply {
            moveTo(w * 0.5f, w * 0.14f)
            lineTo(w * 0.8f, w * 0.82f)
            lineTo(w * 0.5f, w * 0.66f)
            lineTo(w * 0.2f, w * 0.82f)
            close()
        }
        drawPath(p, Color.White)
        drawPath(p, Red, style = Stroke(w * 0.05f, join = StrokeJoin.Round))
    }
}
