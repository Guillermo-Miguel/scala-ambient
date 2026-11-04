package es.scala.ambient.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.Zone
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Smooth = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val Out = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Bienvenida: la línea de luz cruza la pantalla y se recoge en el salpicadero,
 * se dibuja el coche, se encienden las puertas en cadena y aparece el logo.
 * Toca en cualquier sitio para saltarla.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val st = AmbientApp.instance.ble.state.value
    val dashColor = if (st.on) st.zone(Zone.DASH).rgb.rgb() else Ink
    val doorColor = if (st.on) st.zone(Zone.FL).rgb.rgb() else Red

    val sweep = remember { Animatable(0f) }        // 0 → 1 se abre, luego se recoge
    val sweepOut = remember { Animatable(1f) }
    val car = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    val lamps = remember { Animatable(0f) }
    val strips = remember { List(5) { Animatable(0f) } }
    val logo = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val cta = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { delay(350); sweep.animateTo(1f, tween(2200, easing = Smooth)) }
            launch { delay(2600); sweepOut.animateTo(0f, tween(600)) }
            launch { delay(1100); car.animateTo(1f, tween(1400, easing = Out)) }
            launch { delay(1200); draw.animateTo(1f, tween(1900, easing = Smooth)) }
            launch { delay(2300); lamps.animateTo(1f, tween(800)) }
            val starts = listOf(2450L, 2800L, 3050L, 3300L, 3500L)
            strips.forEachIndexed { i, a -> launch { delay(starts[i]); a.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) } }
            launch { delay(3700); title.animateTo(1f, tween(1100, easing = Out)) }
            launch { delay(4000); logo.animateTo(1f, tween(1600, easing = Out)) }
            launch { delay(4900); cta.animateTo(1f, tween(900, easing = Out)) }
        }
        delay(1600)
        onDone()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Saltar") { onDone() },
    ) {
        val carH = (maxHeight * 0.52f).coerceAtMost(360.dp)
        val carW = carH * (260f / 560f)

        // brillo de fondo con el color de las puertas
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (-80).dp).size(520.dp).alpha(0.18f * strips[1].value)
                .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(140.dp) else Modifier.alpha(0.4f))
                .background(doorColor, CircleShape)
        )

        // aviso de puerta
        Row(
            Modifier.padding(24.dp).alpha(car.value).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, GlassEdge, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Dot(Red)
            Text("Puerta del conductor abierta", color = Quiet, fontSize = 14.sp)
        }

        // línea de luz
        val lineW = if (sweep.value < 0.48f) maxWidth * (sweep.value / 0.48f) * 0.86f
        else carW * (94f / 156f) + (maxWidth * 0.86f - carW * (94f / 156f)) * (1f - (sweep.value - 0.48f) / 0.52f)
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = 28.dp + carH * (243f / 560f)).width(lineW).height(3.dp)
                .alpha(sweepOut.value * (sweep.value * 8f).coerceAtMost(1f))
                .shadow(12.dp, RoundedCornerShape(2.dp), ambientColor = dashColor, spotColor = dashColor)
                .clip(RoundedCornerShape(2.dp)).background(dashColor)
        )

        // coche
        CarLineArt(
            draw = draw.value, lamps = lamps.value, strips = strips.map { it.value },
            dashColor = dashColor, doorColor = doorColor,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp).height(carH).width(carW)
                .graphicsLayer { alpha = car.value; scaleX = 0.94f + 0.06f * car.value; scaleY = scaleX },
        )

        // texto y logo
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 28.dp + carH + 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "Bienvenido", color = Ink, fontSize = 46.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 18.dp.toPx() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(28.dp * logo.value).height(2.dp).background(Ink))
                BrandLogo(16.dp, Modifier.graphicsLayer { alpha = logo.value })
                Dot(Red.copy(alpha = logo.value), 6)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.graphicsLayer { alpha = cta.value; translationY = (1f - cta.value) * 18.dp.toPx() }
                    .height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(26.dp))
                    .clickable(role = Role.Button) { onDone() }.padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Empezar", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Icon(Icons.Arrow, null, tint = Ink, modifier = Modifier.size(16.dp))
            }
        }
    }
}
