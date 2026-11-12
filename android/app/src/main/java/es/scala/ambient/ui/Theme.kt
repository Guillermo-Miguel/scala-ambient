package es.scala.ambient.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.R

// ---------- Colores: negro y blanco, rojo como complementario ----------
val Ground = Color(0xFF050505)
val Ink = Color(0xFFF5F5F7)
val Quiet = Color(0xFFEBEBF5).copy(alpha = 0.64f)
val Faint = Color(0xFFEBEBF5).copy(alpha = 0.38f)
val Red = Color(0xFFE4002B)
val RedText = Color(0xFFFF4D5E)     // rojo legible para texto pequeño sobre negro
val GlassFill = Color.White.copy(alpha = 0.065f)
val GlassEdge = Color.White.copy(alpha = 0.12f)

fun Int.rgb() = Color(0xFF000000.toInt() or this)

/** Panel de cristal: translúcido, borde fino y brillo superior. */
@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 30.dp,
    padding: PaddingValues = PaddingValues(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(GlassFill)
            // brillo superior del cristal
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.045f), Color.Transparent), endY = 220f))
            .border(BorderStroke(1.dp, GlassEdge), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Pastilla tipo "segmentos": la opción elegida queda en blanco. */
@Composable
fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, GlassEdge, RoundedCornerShape(24.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Ink else Color.Transparent, tween(320), label = "seg")
            val fg by animateColorAsState(if (on) Ground else Quiet, tween(320), label = "segfg")
            Box(
                Modifier.weight(1f, fill = false).height(44.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                    .clickable(role = Role.Button) { onPick(i) }.padding(horizontal = if (LocalCarMode.current) 10.dp else 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(l, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false) }
        }
    }
}

/** Tu logotipo, en blanco. */
@Composable
fun BrandLogo(height: Dp, modifier: Modifier = Modifier, tint: Color = Ink) {
    Image(
        painter = painterResource(R.drawable.logo_skoda),
        contentDescription = "Škoda",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.height(height).aspectRatio(1008f / 148f),
    )
}

/** Marca del sistema: línea de luz + punto rojo. */
@Composable
fun LightMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(22.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(Ink))
        Box(Modifier.size(5.dp).clip(CircleShape).background(Red))
    }
}

// ---------- Iconos de trazo fino (24×24), mismos dibujos que el diseño ----------
private fun strokeIcon(name: String, vararg d: String, width: Float = 1.7f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        d.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun fillIcon(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White))
    }.build()

object Icons {
    val Home = strokeIcon("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val Nav = strokeIcon("nav", "M3 11 21 3l-8 18-2-8z")
    val Music = strokeIcon("music", "M9 18V5l12-2v13", "M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M18 13a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val Phone = strokeIcon("phone", "M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z")
    val Light = strokeIcon("light", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val Settings = strokeIcon("settings", "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6")
    val Search = strokeIcon("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M21 21l-4.3-4.3")
    val Locate = strokeIcon("locate", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v3M12 19v3M2 12h3M19 12h3")
    val Close = strokeIcon("close", "M6 6l12 12M18 6L6 18")
    val Chevron = strokeIcon("chev", "M9 6l6 6-6 6")
    val Back = strokeIcon("back", "M15 6l-6 6 6 6")
    val Star = strokeIcon("star", "M12 3l2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z")
    val Work = strokeIcon("work", "M3 8h18v12H3z", "M8 8V5h8v3")
    val Dialpad = strokeIcon("dial", "M6 5h.01M12 5h.01M18 5h.01M6 11h.01M12 11h.01M18 11h.01M6 17h.01M12 17h.01M18 17h.01", width = 3.2f)
    val Sun = strokeIcon("sun", "M17 18a5 5 0 0 0-10 0", "M12 9V2M4.2 10.2l1.4 1.4M1 18h2M21 18h2M18.4 11.6l1.4-1.4M23 22H1")
    val Bolt = strokeIcon("bolt", "M13 2 3 14h9l-1 8 10-12h-9z")
    val Moon = strokeIcon("moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Cloud = strokeIcon("cloud", "M7 18a5 5 0 1 1 1-9.9A6 6 0 0 1 19 10a4 4 0 0 1 0 8z")
    val Replay = strokeIcon("replay", "M3 12a9 9 0 1 0 3-6.7", "M3 4v5h5")
    val Arrow = strokeIcon("arrow", "M5 12h14M13 6l6 6-6 6", width = 2f)
    val Play = fillIcon("play", "M7 4v16l13-8z")
    val Pause = fillIcon("pause", "M6 4h4v16H6zM14 4h4v16h-4z")
    val Next = fillIcon("next", "M16 5h2v14h-2zM4 5v14l11-7z")
    val Prev = fillIcon("prev", "M6 5h2v14H6zM20 5v14L9 12z")
    val MicOff = strokeIcon("micoff", "M9 9v2a3 3 0 0 0 5.1 2.1M15 9.3V5a3 3 0 0 0-5.9-.7", "M19 10v1a7 7 0 0 1-11.9 5M5 10v1a7 7 0 0 0 .6 2.8", "M12 18v4", "M3 3l18 18")
    val Mic = strokeIcon("mic", "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v1a7 7 0 0 1-14 0v-1", "M12 18v4")
    val Speaker = strokeIcon("speaker", "M11 5L6 9H2v6h4l5 4z", "M15.5 8.5a5 5 0 0 1 0 7", "M19 5a10 10 0 0 1 0 14")
    val Drop = strokeIcon("drop", "M12 3s6 6.6 6 11a6 6 0 0 1-12 0c0-4.4 6-11 6-11z")
    val Humidity = strokeIcon("hum", "M12 3s6 6.6 6 11a6 6 0 0 1-12 0c0-4.4 6-11 6-11z", "M9 15h6")
    val Wind = strokeIcon("wind", "M3 8h11a3 3 0 1 0-3-3", "M3 12h16a3 3 0 1 1-3 3", "M3 16h7")
    val Rain = strokeIcon("rain", "M7 15a5 5 0 1 1 1-9.9A6 6 0 0 1 19 7a4 4 0 0 1 0 8z", "M8 19l-1 2M12 19l-1 2M16 19l-1 2")
    val Snow = strokeIcon("snow", "M7 15a5 5 0 1 1 1-9.9A6 6 0 0 1 19 7a4 4 0 0 1 0 8z", "M8 19h.01M12 21h.01M16 19h.01", width = 2.4f)
    val Storm = strokeIcon("storm", "M7 15a5 5 0 1 1 1-9.9A6 6 0 0 1 19 7a4 4 0 0 1 0 8z", "M13 13l-3 5h4l-3 5")
    val Fog = strokeIcon("fog", "M4 9h16M2 13h20M5 17h14")
    val SunFull = strokeIcon("sunfull", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val PartlyCloudy = strokeIcon("pcloud", "M12 4V2M5.6 6.6 4.2 5.2M18.4 6.6l1.4-1.4M8.5 9a4 4 0 0 1 7.4 1", "M7 20a4 4 0 1 1 .8-7.9A5 5 0 0 1 17 13a3.5 3.5 0 0 1 0 7z")
    val Gauge = strokeIcon("gauge", "M12 21a9 9 0 1 1 0-18a9 9 0 1 1 0 18z", "M12 12l4-4")
    val Sunrise = strokeIcon("sunrise", "M17 18a5 5 0 0 0-10 0", "M12 2v7M8 6l4-4 4 4M4.2 10.2l1.4 1.4M1 18h2M21 18h2M18.4 11.6l1.4-1.4M23 22H1")
    val Backspace = strokeIcon("bksp", "M21 5H8l-6 7 6 7h13z", "M12 9l6 6M18 9l-6 6")
}
