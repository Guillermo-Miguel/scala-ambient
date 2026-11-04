package es.scala.ambient.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Smooth = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
private val Out = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Bienvenida:
 * 1. Una línea de luz blanca cruza la pantalla.
 * 2. Se recoge en el centro y "dibuja" el logo de izquierda a derecha.
 * 3. Un destello recorre el logo y el rojo se extiende por toda la pantalla desde abajo.
 * 4. Aparece «Bienvenido, <nombre>».
 * Toca en cualquier sitio para saltarla.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val settings by AmbientApp.instance.prefs.settings.collectAsState()
    val name = settings.driverName.trim()

    val line = remember { Animatable(0f) }       // 0 → 1: la línea se abre de lado a lado
    val lineOut = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }     // 0 → 1: el logo aparece de izquierda a derecha
    val shine = remember { Animatable(0f) }      // destello que recorre el logo
    val glow = remember { Animatable(0f) }       // rojo de fondo
    val title = remember { Animatable(0f) }
    val cta = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { delay(250); line.animateTo(1f, tween(1300, easing = Smooth)) }
            launch { delay(1450); reveal.animateTo(1f, tween(1500, easing = Smooth)) }
            launch { delay(1450); lineOut.animateTo(0f, tween(1500, easing = Smooth)) }
            launch { delay(2600); glow.animateTo(1f, tween(2200, easing = Out)) }
            launch { delay(2900); shine.animateTo(1f, tween(1300, easing = LinearEasing)) }
            launch { delay(3500); title.animateTo(1f, tween(1100, easing = Out)) }
            launch { delay(4300); cta.animateTo(1f, tween(900, easing = Out)) }
        }
        delay(1500)
        onDone()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // rojo que sube desde abajo y ocupa toda la pantalla (sin bordes cuadrados)
            .drawBehind {
                val g = glow.value
                if (g > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            0f to Red.copy(alpha = 0.55f * g),
                            0.45f to Red.copy(alpha = 0.18f * g),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height * 1.08f),
                            radius = size.maxDimension * (0.35f + 0.75f * g),
                        )
                    )
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), endY = size.height * 0.45f)
                    )
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Saltar") { onDone() },
    ) {
        val fullW = maxWidth
        val logoW = (fullW * 0.46f).coerceAtMost(560.dp)
        val logoH = logoW * (148f / 1008f)

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.width(fullW).height(logoH + 40.dp), contentAlignment = Alignment.Center) {
                // la línea de luz
                Box(
                    Modifier.width(fullW * 0.9f * line.value).height(2.dp)
                        .graphicsLayer { alpha = lineOut.value * (line.value * 6f).coerceAtMost(1f) }
                        .drawBehind {
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Ink, Color.Transparent)))
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent)),
                                topLeft = Offset(0f, -10f), size = Size(size.width, size.height + 20f),
                            )
                        }
                )
                // el logo, revelado de izquierda a derecha con un borde de luz y un destello después
                Box(
                    Modifier.width(logoW).height(logoH)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            val edge = size.width * reveal.value
                            clipRect(right = edge) { this@drawWithContent.drawContent() }
                            if (reveal.value in 0.001f..0.999f) {
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent), startX = edge - 30f, endX = edge + 30f),
                                    topLeft = Offset(edge - 30f, -size.height * 0.4f), size = Size(60f, size.height * 1.8f),
                                )
                            }
                            val sx = -size.width * 0.3f + size.width * 1.6f * shine.value
                            if (shine.value in 0.001f..0.999f) {
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(Color.Transparent, Color.White.copy(alpha = 0.9f), Color.Transparent),
                                        start = Offset(sx - 80f, 0f), end = Offset(sx + 80f, size.height),
                                    ),
                                    blendMode = BlendMode.SrcAtop,
                                )
                            }
                        }
                ) {
                    BrandLogo(logoH, tint = Ink)
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                if (name.isBlank()) "Bienvenido" else "Bienvenido, $name",
                color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 16.dp.toPx() },
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.graphicsLayer { alpha = title.value },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(24.dp * title.value).height(2.dp).background(Ink))
                Dot(Red, 6)
                Box(Modifier.width(24.dp * title.value).height(2.dp).background(Ink))
            }
            Spacer(Modifier.height(36.dp))
            Row(
                Modifier.graphicsLayer { alpha = cta.value; translationY = (1f - cta.value) * 16.dp.toPx() }
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
