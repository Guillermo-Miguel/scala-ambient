package es.scala.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.nav.DayWeather
import es.scala.ambient.nav.NavModel
import es.scala.ambient.nav.Weather
import es.scala.ambient.nav.weatherLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

fun deg(c: Double): String = "${c.roundToInt()}°"

fun weatherIcon(code: Int, isDay: Boolean = true): ImageVector = when (code) {
    0 -> if (isDay) Icons.SunFull else Icons.Moon
    1, 2 -> if (isDay) Icons.PartlyCloudy else Icons.Moon
    3 -> Icons.Cloud
    45, 48 -> Icons.Fog
    in 51..67, in 80..82 -> Icons.Rain
    in 71..77, 85, 86 -> Icons.Snow
    in 95..99 -> Icons.Storm
    else -> Icons.Cloud
}

private val Es = Locale("es", "ES")

/** El tiempo en detalle: se abre al tocar el mosaico de Inicio. Toca fuera o ✕ para cerrar. */
@Composable
fun WeatherPanel(wide: Boolean, onClose: () -> Unit) {
    val w by NavModel.weather.collectAsState()
    LaunchedEffect(Unit) { NavModel.refreshWeather(force = true) }

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp)).background(Color.Black.copy(alpha = 0.85f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Cerrar") { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding(if (wide) 8.dp else 0.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFA0E0E10))                       // fondo oscuro casi opaco
                .border(1.dp, GlassEdge, RoundedCornerShape(30.dp))
                // los toques dentro del panel no lo cierran
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(22.dp),
        ) {
            val weather = w
            if (weather == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Cargando el tiempo…", color = Quiet, fontSize = 17.sp)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Cerrar", onClick = onClose)
                }
                return@Box
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().carDrag().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Now(weather, onClose)
                        Stats(weather, columns = 4)
                        Hours(weather)
                    }
                    Days(weather.days, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().carDrag().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Now(weather, onClose)
                    Stats(weather, columns = 2)
                    Hours(weather)
                    Days(weather.days, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Now(w: Weather, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(w.place ?: "Tu posición")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(deg(w.tempC), color = Ink, fontSize = 72.sp, fontWeight = FontWeight.Light, lineHeight = 72.sp)
                Icon(weatherIcon(w.code, w.isDay), null, tint = Ink, modifier = Modifier.size(52.dp))
                Column {
                    Text(w.label, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    w.today?.let { Text("Máx. ${deg(it.maxC)} · Mín. ${deg(it.minC)}", color = Quiet, fontSize = 15.sp) }
                    Text("Sensación ${deg(w.feelsC)}", color = Quiet, fontSize = 15.sp)
                }
            }
        }
        RoundIcon(Icons.Close, "Cerrar", size = 48, onClick = onClose)
    }
}

@Composable
private fun Stats(w: Weather, columns: Int) {
    val items = listOf(
        Triple(Icons.Drop, "Prob. de lluvia", "${w.rainProbNow} %"),
        Triple(Icons.Rain, "Lluvia hoy", String.format(Es, "%.1f mm", w.today?.rainMm ?: w.precipMm)),
        Triple(Icons.Humidity, "Humedad", "${w.humidity} %"),
        Triple(Icons.Wind, "Viento", "${w.windKmh.roundToInt()} km/h ${w.windFrom}"),
        Triple(Icons.Wind, "Rachas", "${w.gustKmh.roundToInt()} km/h"),
        Triple(Icons.SunFull, "Índice UV", String.format(Es, "%.0f", w.uv)),
        Triple(Icons.Gauge, "Presión", "${w.pressure.roundToInt()} hPa"),
        Triple(Icons.Sunrise, "Sol", "${w.sunrise ?: "—"} · ${w.sunset ?: "—"}"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label, value) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, null, tint = Quiet, modifier = Modifier.size(14.dp))
                            Text(label, color = Quiet, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Hours(w: Weather) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Próximas horas")
        Row(Modifier.carDrag(vertical = false, horizontal = true).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            w.hours.forEachIndexed { i, h ->
                Column(
                    Modifier.width(64.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (i == 0) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (i == 0) "Ahora" else "${two(h.hour)} h", color = Quiet, fontSize = 12.sp)
                    Icon(weatherIcon(h.code, h.hour in 7..20), null, tint = Ink, modifier = Modifier.size(22.dp))
                    Text(deg(h.tempC), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${h.rainProb} %", color = if (h.rainProb >= 50) RedText else Faint, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Days(days: List<DayWeather>, modifier: Modifier) {
    val lo = days.minOfOrNull { it.minC } ?: 0.0
    val hi = days.maxOfOrNull { it.maxC } ?: 1.0
    val span = (hi - lo).coerceAtLeast(1.0)
    Column(modifier.carDrag().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Próximos días")
        days.forEachIndexed { i, d ->
            val name = if (i == 0) "Hoy" else runCatching {
                LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Es).replaceFirstChar { it.uppercase() }
            }.getOrDefault(d.date)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = if (i == 0) 0.08f else 0.03f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
                Icon(weatherIcon(d.code), weatherLabel(d.code), tint = Ink, modifier = Modifier.size(22.dp))
                Text("${d.rainProb} %", color = if (d.rainProb >= 50) RedText else Faint, fontSize = 13.sp, modifier = Modifier.width(42.dp))
                Text(deg(d.minC), color = Quiet, fontSize = 15.sp, modifier = Modifier.width(36.dp))
                // barra de temperaturas del día dentro del rango de la semana
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f))) {
                    val start = ((d.minC - lo) / span).toFloat()
                    val end = ((d.maxC - lo) / span).toFloat()
                    Row(Modifier.fillMaxSize()) {
                        if (start > 0f) Spacer(Modifier.weight(start))
                        Box(
                            Modifier.weight((end - start).coerceAtLeast(0.04f)).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                                .background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.6f), Red)))
                        )
                        if (end < 1f) Spacer(Modifier.weight(1f - end))
                    }
                }
                Text(deg(d.maxC), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
            }
        }
    }
}
