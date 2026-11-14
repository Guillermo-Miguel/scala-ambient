package es.scala.ambient.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import es.scala.ambient.Zone

/**
 * Coche compacto de cinco puertas visto desde arriba, sin techo, a escala real:
 * 4.362 × 1.793 mm, batalla 2.649 mm. Unidades de dibujo: 260 × 560.
 */
private object Car {
    const val W = 260f
    const val H = 560f

    fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    val body = p("M130 4 C180 4 226 10 236 40 C242 60 243 90 243 130 L243 440 C243 500 240 530 228 545 C214 556 170 557 130 557 C90 557 46 556 32 545 C20 530 17 500 17 440 L17 130 C17 90 18 60 24 40 C34 10 80 4 130 4 Z")
    val mirrorL = p("M18 204 L5 209 Q1 215 5 222 L18 224 Z")
    val mirrorR = p("M242 204 L255 209 Q259 215 255 222 L242 224 Z")
    val windscreen = p("M34 170 Q130 146 226 170 L222 214 Q130 202 38 214 Z")
    val rearGlass = p("M42 460 Q130 472 218 460 L214 500 Q130 510 46 500 Z")
    val cabin = p("M54 212 H206 Q224 212 224 230 V438 Q224 456 206 456 H54 Q36 456 36 438 V230 Q36 212 54 212 Z")
    val dash = p("M38 216 L222 216 L222 232 Q130 258 38 232 Z")
    val wheel = p("M60 260 A26 9 0 1 0 112 260 A26 9 0 1 0 60 260 Z")
    val seatL = p("M66 276 H98 Q110 276 110 288 V340 Q110 352 98 352 H66 Q54 352 54 340 V288 Q54 276 66 276 Z")
    val seatR = p("M162 276 H194 Q206 276 206 288 V340 Q206 352 194 352 H162 Q150 352 150 340 V288 Q150 276 162 276 Z")
    val bench = p("M62 380 H198 Q212 380 212 394 V430 Q212 444 198 444 H62 Q48 444 48 430 V394 Q48 380 62 380 Z")
    val headL = p("M30 36 Q44 20 80 15")
    val headR = p("M230 36 Q216 20 180 15")
    val tailL = p("M30 530 Q50 546 86 549")
    val tailR = p("M230 530 Q210 546 174 549")
    val bonnet = p("M84 30 Q90 100 92 160 M176 30 Q170 100 168 160")

    /** Tiras de luz, en el orden de [Zone]. */
    val strips = listOf(
        p("M52 233 Q130 254 208 233"),   // salpicadero
        p("M48 254 V344"),               // delantera izquierda
        p("M212 254 V344"),              // delantera derecha
        p("M48 374 V444"),               // trasera izquierda
        p("M212 374 V444"),              // trasera derecha
    )

    /** Dónde cae la luz en el suelo: centro y radios. */
    val spill = listOf(
        floatArrayOf(130f, 254f, 82f, 14f),
        floatArrayOf(58f, 300f, 12f, 44f), floatArrayOf(202f, 300f, 12f, 44f),
        floatArrayOf(58f, 410f, 12f, 36f), floatArrayOf(202f, 410f, 12f, 36f),
    )

    fun hit(x: Float, y: Float): Zone? = when {
        y in 216f..262f && x in 40f..220f -> Zone.DASH
        x < 72f && y in 248f..352f -> Zone.FL
        x > 188f && y in 248f..352f -> Zone.FR
        x < 72f && y in 364f..452f -> Zone.RL
        x > 188f && y in 364f..452f -> Zone.RR
        else -> null
    }
}

private val BodyBrush = Brush.horizontalGradient(listOf(Color(0xFF141416), Color(0xFF26262A), Color(0xFF141416)), 17f, 243f)
private val GlassBrush = Brush.verticalGradient(listOf(Color(0x8C4A4D55), Color(0x6616171B)))
private val Line = Color.White

private fun DrawScope.rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) =
    drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r))

/** Coche con la luz ambiente encendida. Toca una zona para elegirla. */
@Composable
fun CarTopView(
    colors: List<Color>,
    levels: List<Float>,          // 0..1 ya con el efecto aplicado
    selected: Set<Zone>,
    onTap: ((Zone?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .aspectRatio(Car.W / Car.H)
            .semantics { contentDescription = "Habitáculo visto desde arriba. Toca el salpicadero o una puerta." }
            .then(
                if (onTap != null) Modifier.pointerInput(Unit) {
                    detectTapGestures { p -> onTap(Car.hit(p.x / size.width * Car.W, p.y / size.height * Car.H)) }
                } else Modifier
            )
    ) {
        val k = size.width / Car.W
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            // ruedas
            listOf(12f to 76f, 232f to 76f, 12f to 412f, 232f to 412f).forEach { (x, y) ->
                rr(x, y, 16f, 76f, 6f, Color(0xFF0B0B0C))
            }
            drawPath(Car.mirrorL, BodyBrush); drawPath(Car.mirrorR, BodyBrush)
            drawPath(Car.body, BodyBrush)
            drawPath(Car.body, Line.copy(alpha = 0.24f), style = Stroke(1.2f))
            drawPath(Car.bonnet, Line.copy(alpha = 0.08f), style = Stroke(1f))
            drawPath(Car.headL, Ink.copy(alpha = 0.85f), style = Stroke(2.5f, cap = StrokeCap.Round))
            drawPath(Car.headR, Ink.copy(alpha = 0.85f), style = Stroke(2.5f, cap = StrokeCap.Round))
            drawPath(Car.windscreen, GlassBrush)
            drawLine(Line.copy(alpha = 0.28f), Offset(34f, 170f), Offset(38f, 214f), 3f, StrokeCap.Round)
            drawLine(Line.copy(alpha = 0.28f), Offset(226f, 170f), Offset(222f, 214f), 3f, StrokeCap.Round)

            // habitáculo
            drawPath(Car.cabin, Color(0xFF09090A))
            drawPath(Car.cabin, Line.copy(alpha = 0.10f), style = Stroke(1f))
            rr(17f, 352f, 19f, 10f, 0f, Color(0xFF2A2A2D)); rr(224f, 352f, 19f, 10f, 0f, Color(0xFF2A2A2D))
            drawPath(Car.dash, Color(0xFF17171A))
            rr(110f, 219f, 40f, 7f, 2f, Color(0xFF2B2E35))
            drawPath(Car.wheel, Line.copy(alpha = 0.5f), style = Stroke(3f))
            rr(118f, 252f, 24f, 110f, 8f, Color(0xFF121214))
            drawCircle(Color(0xFF2A2A2D), 5f, Offset(130f, 290f))
            rr(119f, 330f, 22f, 34f, 6f, Color(0xFF1C1C1F))
            listOf(54f, 150f).forEach { x ->
                rr(x, 276f, 56f, 48f, 14f, Color(0xFF1B1B1E))
                rr(x - 2, 322f, 60f, 24f, 10f, Color(0xFF232326))
                rr(x + 14, 344f, 28f, 10f, 5f, Color(0xFF2C2C30))
                drawLine(Red.copy(alpha = 0.7f), Offset(x + 28, 282f), Offset(x + 28, 318f), 1f)
            }
            rr(48f, 380f, 164f, 44f, 14f, Color(0xFF1B1B1E))
            rr(46f, 422f, 168f, 22f, 10f, Color(0xFF232326))
            listOf(64f, 116f, 168f).forEach { x -> rr(x, 442f, 28f, 9f, 4.5f, Color(0xFF2C2C30)) }
            drawLine(Red.copy(alpha = 0.7f), Offset(96f, 386f), Offset(96f, 418f), 1f)
            drawLine(Red.copy(alpha = 0.7f), Offset(164f, 386f), Offset(164f, 418f), 1f)
            rr(36f, 236f, 10f, 114f, 4f, Color(0xFF141416)); rr(214f, 236f, 10f, 114f, 4f, Color(0xFF141416))
            rr(36f, 366f, 10f, 84f, 4f, Color(0xFF141416)); rr(214f, 366f, 10f, 84f, 4f, Color(0xFF141416))
            drawPath(Car.rearGlass, GlassBrush)
            drawPath(Car.tailL, Red.copy(alpha = 0.9f), style = Stroke(2.5f, cap = StrokeCap.Round))
            drawPath(Car.tailR, Red.copy(alpha = 0.9f), style = Stroke(2.5f, cap = StrokeCap.Round))

            // luz ambiente: reflejo en el suelo, halo y tira
            Zone.entries.forEach { z ->
                val a = levels[z.ordinal].coerceIn(0f, 1f)
                val c = colors[z.ordinal]
                val s = Car.spill[z.ordinal]
                if (a > 0.01f) {
                    for (i in 4 downTo 1) {
                        val g = i / 4f
                        drawOval(
                            c.copy(alpha = 0.10f * a),
                            Offset(s[0] - s[2] * (1 + g), s[1] - s[3] * (1 + g * 0.6f)),
                            Size(s[2] * 2 * (1 + g), s[3] * 2 * (1 + g * 0.6f)),
                        )
                    }
                    for (w in listOf(14f, 9f, 5f)) {
                        drawPath(Car.strips[z.ordinal], c.copy(alpha = 0.14f * a), style = Stroke(w, cap = StrokeCap.Round))
                    }
                    drawPath(Car.strips[z.ordinal], c.copy(alpha = 0.55f + 0.45f * a), style = Stroke(2.4f, cap = StrokeCap.Round))
                } else {
                    drawPath(Car.strips[z.ordinal], Line.copy(alpha = 0.12f), style = Stroke(2.4f, cap = StrokeCap.Round))
                }
                if (z in selected) {
                    val b = Car.strips[z.ordinal].getBounds()
                    drawRoundRect(
                        Line.copy(alpha = 0.6f), Offset(b.left - 12f, b.top - 12f),
                        Size(b.width + 24f, b.height + 24f), CornerRadius(14f), style = Stroke(1.5f),
                    )
                }
            }
        }
    }
}

/** Versión de líneas para la bienvenida: se va dibujando (0..1) y luego se encienden las tiras. */
@Composable
fun CarLineArt(
    draw: Float,
    lamps: Float,
    strips: List<Float>,
    dashColor: Color,
    doorColor: Color,
    modifier: Modifier = Modifier,
) {
    val outline = remember { listOf(Car.body, Car.mirrorL, Car.mirrorR, Car.windscreen, Car.rearGlass, Car.cabin, Car.dash, Car.wheel, Car.seatL, Car.seatR, Car.bench) }
    val measure = remember { PathMeasure() }
    Canvas(modifier.aspectRatio(Car.W / Car.H)) {
        val k = size.width / Car.W
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            outline.forEachIndexed { i, path ->
                // cada pieza empieza un poco después de la anterior
                val start = i * 0.05f
                val t = ((draw - start) / (1f - start * 0.6f)).coerceIn(0f, 1f)
                if (t <= 0f) return@forEachIndexed
                measure.setPath(path, false)
                val seg = Path()
                measure.getSegment(0f, measure.length * t, seg, true)
                drawPath(seg, Line.copy(alpha = 0.5f), style = Stroke(1.6f, cap = StrokeCap.Round))
            }
            if (lamps > 0f) {
                listOf(Car.headL, Car.headR).forEach { drawPath(it, Ink.copy(alpha = lamps), style = Stroke(2.6f, cap = StrokeCap.Round)) }
                listOf(Car.tailL, Car.tailR).forEach { drawPath(it, Red.copy(alpha = lamps), style = Stroke(2.6f, cap = StrokeCap.Round)) }
            }
            Zone.entries.forEach { z ->
                val a = strips[z.ordinal].coerceIn(0f, 1f)
                if (a <= 0f) return@forEach
                val c = if (z == Zone.DASH) dashColor else doorColor
                for (w in listOf(16f, 10f, 6f)) drawPath(Car.strips[z.ordinal], c.copy(alpha = 0.16f * a), style = Stroke(w, cap = StrokeCap.Round))
                drawPath(Car.strips[z.ordinal], c.copy(alpha = a), style = Stroke(2.6f, cap = StrokeCap.Round))
            }
        }
    }
}
