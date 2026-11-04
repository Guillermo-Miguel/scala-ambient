package es.scala.ambient.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import es.scala.ambient.NavApp
import es.scala.ambient.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Ruta calculada: puntos para dibujarla, distancia y duración. */
data class Route(val points: List<Pair<Double, Double>>, val meters: Double, val seconds: Double)

data class Weather(val tempC: Double, val code: Int) {
    val label: String get() = when (code) {
        0 -> "Despejado"
        1, 2 -> "Poco nuboso"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        in 51..57 -> "Llovizna"
        in 61..67, in 80..82 -> "Lluvia"
        in 71..77, 85, 86 -> "Nieve"
        in 95..99 -> "Tormenta"
        else -> "—"
    }
}

/**
 * Servicios abiertos, sin clave de API:
 * - Búsqueda de direcciones: Nominatim (OpenStreetMap). Máximo 1 búsqueda por segundo.
 * - Ruta: servidor público de OSRM (pensado para uso ligero, no comercial).
 * - Tiempo: Open-Meteo.
 * La navegación paso a paso se abre en Google Maps o Waze.
 */
object Net {
    private const val UA = "ScalaAmbient/1.0 (uso personal)"

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es")
        c.connectTimeout = 8000
        c.readTimeout = 10000
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(query: String, near: Pair<Double, Double>?): List<Place> {
        if (query.isBlank()) return emptyList()
        val bias = near?.let { (lat, lon) ->
            val d = 1.5
            "&viewbox=${lon - d},${lat + d},${lon + d},${lat - d}"
        } ?: ""
        val json = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&addressdetails=0&countrycodes=es,pt,fr,ad&q=${enc(query)}$bias"))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(",") }
            Place(name, full, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    suspend fun route(from: Pair<Double, Double>, to: Pair<Double, Double>): Route {
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            from.second, from.first, to.second, to.first,
        )
        val r = JSONObject(get(url)).getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i)
            c.getDouble(1) to c.getDouble(0)
        }
        return Route(pts, r.getDouble("distance"), r.getDouble("duration"))
    }

    suspend fun weather(lat: Double, lon: Double): Weather {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code",
            lat, lon,
        )
        val cur = JSONObject(get(url)).getJSONObject("current")
        return Weather(cur.getDouble("temperature_2m"), cur.getInt("weather_code"))
    }

    /** Abre la navegación paso a paso en la app elegida (en el coche sale en Android Auto). */
    fun startNavigation(context: Context, app: NavApp, lat: Double, lon: Double) {
        val intent = when (app) {
            NavApp.GOOGLE_MAPS -> Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=d"))
                .setPackage("com.google.android.apps.maps")
            NavApp.WAZE -> Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes"))
                .setPackage("com.waze")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // La app no está instalada: abrir con lo que haya
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(Locale("es", "ES"), "%.1f km", m / 1000)

fun formatDuration(s: Double): String {
    val min = (s / 60).toInt()
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}
