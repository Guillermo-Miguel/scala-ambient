package es.scala.ambient.nav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Una maniobra de la ruta (lo que devuelve OSRM en cada "step"). */
data class Step(
    val type: String,              // depart, turn, new name, continue, merge, on ramp, off ramp, fork, end of road, roundabout, rotary, exit roundabout, arrive…
    val modifier: String?,         // left, right, slight left, sharp right, straight, uturn
    val name: String,
    val ref: String?,
    val destinations: String?,
    val exit: Int?,
    val distance: Double,          // metros hasta la siguiente maniobra
    val duration: Double,
    val lat: Double,
    val lon: Double,
    val bearingAfter: Int,
) {
    /** Calle o carretera, con su referencia si la tiene ("Calle Recogidas", "A-44"). */
    val road: String get() = listOfNotNull(name.ifBlank { null }, ref?.ifBlank { null }).distinct().joinToString(" · ")

    private val towards: String get() = destinations?.substringBefore(",")?.trim()?.ifBlank { null }?.let { " hacia $it" } ?: ""

    private val onto: String get() = road.ifBlank { null }?.let { " por $it" } ?: ""

    private val side: String get() = when {
        modifier == null -> ""
        modifier.contains("left") -> "izquierda"
        modifier.contains("right") -> "derecha"
        else -> ""
    }

    private val turnVerb: String get() = when (modifier) {
        "uturn" -> "Cambia de sentido"
        "sharp left" -> "Gira bruscamente a la izquierda"
        "sharp right" -> "Gira bruscamente a la derecha"
        "slight left" -> "Gira ligeramente a la izquierda"
        "slight right" -> "Gira ligeramente a la derecha"
        "left" -> "Gira a la izquierda"
        "right" -> "Gira a la derecha"
        else -> "Sigue recto"
    }

    private fun ordinal(n: Int) = when (n) {
        1 -> "primera"; 2 -> "segunda"; 3 -> "tercera"; 4 -> "cuarta"; 5 -> "quinta"; 6 -> "sexta"
        else -> "${n}ª"
    }

    /** Instrucción en español, como la diría un copiloto. */
    val text: String get() = when (type) {
        "depart" -> "Sal" + (road.ifBlank { null }?.let { " por $it" } ?: " hacia el ${compass(bearingAfter)}")
        "arrive" -> when {
            side.isNotEmpty() -> "Has llegado. El destino está a la $side"
            else -> "Has llegado a tu destino"
        }
        "turn", "end of road" -> (if (type == "end of road") "Al final de la calle, " + turnVerb.replaceFirstChar { it.lowercase() } else turnVerb) + onto
        "new name" -> "Continúa$onto"
        "continue" -> if (modifier == null || modifier == "straight") "Sigue recto$onto" else turnVerb + onto
        "merge" -> "Incorpórate" + (road.ifBlank { null }?.let { " a $it" } ?: if (side.isNotEmpty()) " por la $side" else "")
        "on ramp" -> "Toma el acceso" + (if (side.isNotEmpty()) " de la $side" else "") + (towards.ifEmpty { onto })
        "off ramp" -> "Toma la salida" + (if (side.isNotEmpty()) " de la $side" else "") + (towards.ifEmpty { onto })
        "fork" -> "En la bifurcación, mantente a la " + (side.ifEmpty { "derecha" }) + (towards.ifEmpty { onto })
        "roundabout", "rotary" -> if (exit != null) "En la rotonda, toma la ${ordinal(exit)} salida$onto" else "Entra en la rotonda$onto"
        "roundabout turn" -> turnVerb + " en la rotonda" + onto
        "exit roundabout", "exit rotary" -> "Sal de la rotonda$onto"
        else -> if (modifier != null && modifier != "straight") turnVerb + onto else "Continúa$onto"
    }
}

/** Ruta lista para guiar: puntos, distancias acumuladas y maniobras situadas sobre los puntos. */
class GuidedRoute(val route: Route) {
    val pts = route.points
    val cum: DoubleArray = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + dist(pts[i - 1], pts[i])
    }
    val total: Double get() = cum.lastOrNull() ?: 0.0

    /** Para cada maniobra, el índice del punto de la ruta donde ocurre (siempre hacia delante). */
    val stepIndex: IntArray = IntArray(route.steps.size).also { idx ->
        var from = 0
        route.steps.forEachIndexed { k, s ->
            var best = from
            var bestD = Double.MAX_VALUE
            val until = if (k == route.steps.lastIndex) pts.size else minOf(pts.size, from + 4000)
            for (i in from until until) {
                val d = dist(s.lat to s.lon, pts[i])
                if (d < bestD) { bestD = d; best = i }
                if (bestD < 1.0) break
            }
            idx[k] = best
            from = best
        }
    }
}

// ---------- Geometría (aproximación plana local, de sobra para guiar) ----------

private const val R = 6_371_000.0

fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val lat = (a.first + b.first) / 2 * PI / 180
    val dx = (b.second - a.second) * PI / 180 * R * cos(lat)
    val dy = (b.first - a.first) * PI / 180 * R
    return sqrt(dx * dx + dy * dy)
}

/** Proyecta p sobre el segmento a-b: devuelve (fracción 0..1, distancia en metros). */
fun project(p: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
    val lat = a.first * PI / 180
    val kx = PI / 180 * R * cos(lat)
    val ky = PI / 180 * R
    val ax = 0.0; val ay = 0.0
    val bx = (b.second - a.second) * kx; val by = (b.first - a.first) * ky
    val px = (p.second - a.second) * kx; val py = (p.first - a.first) * ky
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0)
    val qx = bx * t; val qy = by * t
    return t to sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
}

fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Float {
    val la1 = a.first * PI / 180; val la2 = b.first * PI / 180
    val dl = (b.second - a.second) * PI / 180
    val y = sin(dl) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
    return ((atan2(y, x) * 180 / PI + 360) % 360).toFloat()
}

fun compass(deg: Int): String =
    listOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")[((deg + 22.5) / 45).toInt().mod(8)]

/** Distancia para mostrar: "350 m", "1,2 km". */
fun shortDistance(m: Double): String = when {
    m < 30 -> "Ahora"
    m < 1000 -> "${((m / 10).roundToInt() * 10)} m"
    m < 10_000 -> String.format(java.util.Locale("es", "ES"), "%.1f km", m / 1000)
    else -> "${(m / 1000).roundToInt()} km"
}

/** Distancia para decir en voz alta: "300 metros", "1,5 kilómetros". */
fun spokenDistance(m: Double): String = when {
    m < 1000 -> "${((m / 50).roundToInt().coerceAtLeast(1) * 50)} metros"
    m < 1500 -> "1 kilómetro"
    else -> {
        val km = (m / 500).roundToInt() / 2.0
        if (km % 1.0 == 0.0) "${km.toInt()} kilómetros" else String.format(java.util.Locale("es", "ES"), "%.1f kilómetros", km)
    }
}
