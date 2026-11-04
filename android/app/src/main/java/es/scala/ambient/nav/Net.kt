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
data class Route(
    val points: List<Pair<Double, Double>>,
    val meters: Double,
    val seconds: Double,
    val steps: List<Step> = emptyList(),
)

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Despejado"
    1 -> "Casi despejado"
    2 -> "Poco nuboso"
    3 -> "Nublado"
    45, 48 -> "Niebla"
    in 51..57 -> "Llovizna"
    in 61..65 -> "Lluvia"
    66, 67 -> "Lluvia helada"
    in 71..77 -> "Nieve"
    in 80..82 -> "Chubascos"
    85, 86 -> "Chubascos de nieve"
    in 95..99 -> "Tormenta"
    else -> "—"
}

data class HourWeather(val hour: Int, val tempC: Double, val rainProb: Int, val code: Int)
data class DayWeather(val date: String, val code: Int, val maxC: Double, val minC: Double, val rainProb: Int, val rainMm: Double)

/** Tiempo actual, próximas horas y próximos días (Open-Meteo). */
data class Weather(
    val place: String?,
    val tempC: Double,
    val feelsC: Double,
    val humidity: Int,
    val precipMm: Double,
    val windKmh: Double,
    val windDir: Int,
    val gustKmh: Double,
    val code: Int,
    val isDay: Boolean,
    val uv: Double,
    val pressure: Double,
    val sunrise: String?,
    val sunset: String?,
    val hours: List<HourWeather>,
    val days: List<DayWeather>,
) {
    val label: String get() = weatherLabel(code)
    val today: DayWeather? get() = days.firstOrNull()
    val rainProbNow: Int get() = hours.firstOrNull()?.rainProb ?: today?.rainProb ?: 0

    val windFrom: String get() {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return dirs[((windDir + 22.5) / 45).toInt().mod(8)]
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

    /**
     * Ruta en coche con sus maniobras. `heading` (grados) ayuda a no mandarte en sentido contrario
     * al recalcular en marcha.
     */
    suspend fun route(from: Pair<Double, Double>, to: Pair<Double, Double>, heading: Float? = null): Route {
        val bearings = heading?.let { "&bearings=${it.toInt()},60;" } ?: ""
        val url = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=false",
            from.second, from.first, to.second, to.first,
        ) + bearings
        val r = JSONObject(get(url)).getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { i ->
            val c = coords.getJSONArray(i)
            c.getDouble(1) to c.getDouble(0)
        }
        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val ss = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until ss.length()) {
                val st = ss.getJSONObject(i)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                steps += Step(
                    type = m.optString("type"),
                    modifier = m.optString("modifier").ifBlank { null },
                    name = st.optString("name"),
                    ref = st.optString("ref").ifBlank { null },
                    destinations = st.optString("destinations").ifBlank { null },
                    exit = if (m.has("exit")) m.optInt("exit") else null,
                    distance = st.optDouble("distance", 0.0),
                    duration = st.optDouble("duration", 0.0),
                    lat = loc.getDouble(1),
                    lon = loc.getDouble(0),
                    bearingAfter = m.optInt("bearing_after"),
                )
            }
        }
        return Route(pts, r.getDouble("distance"), r.getDouble("duration"), steps)
    }

    suspend fun weather(lat: Double, lon: Double): Weather {
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,is_day," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m,pressure_msl,uv_index" +
                "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=12" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,sunrise,sunset" +
                "&forecast_days=6&timezone=auto",
            lat, lon,
        )
        val root = JSONObject(get(url))
        val cur = root.getJSONObject("current")
        val h = root.getJSONObject("hourly")
        val hTime = h.getJSONArray("time"); val hT = h.getJSONArray("temperature_2m")
        val hP = h.getJSONArray("precipitation_probability"); val hC = h.getJSONArray("weather_code")
        val hours = (0 until hTime.length()).map { i ->
            HourWeather(hTime.getString(i).substring(11, 13).toInt(), hT.optDouble(i), hP.optInt(i), hC.optInt(i))
        }
        val d = root.getJSONObject("daily")
        val dTime = d.getJSONArray("time")
        val days = (0 until dTime.length()).map { i ->
            DayWeather(
                dTime.getString(i), d.getJSONArray("weather_code").optInt(i),
                d.getJSONArray("temperature_2m_max").optDouble(i), d.getJSONArray("temperature_2m_min").optDouble(i),
                d.getJSONArray("precipitation_probability_max").optInt(i), d.getJSONArray("precipitation_sum").optDouble(i),
            )
        }
        val place = runCatching { placeName(lat, lon) }.getOrNull()
        return Weather(
            place = place,
            tempC = cur.getDouble("temperature_2m"),
            feelsC = cur.optDouble("apparent_temperature"),
            humidity = cur.optInt("relative_humidity_2m"),
            precipMm = cur.optDouble("precipitation", 0.0),
            windKmh = cur.optDouble("wind_speed_10m", 0.0),
            windDir = cur.optInt("wind_direction_10m"),
            gustKmh = cur.optDouble("wind_gusts_10m", 0.0),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            uv = cur.optDouble("uv_index", 0.0),
            pressure = cur.optDouble("pressure_msl", 0.0),
            sunrise = d.getJSONArray("sunrise").optString(0, "").takeIf { it.length >= 16 }?.substring(11, 16),
            sunset = d.getJSONArray("sunset").optString(0, "").takeIf { it.length >= 16 }?.substring(11, 16),
            hours = hours,
            days = days,
        )
    }

    /** Nombre de la localidad para una posición (Nominatim, búsqueda inversa). */
    suspend fun placeName(lat: Double, lon: Double): String? {
        val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=%.5f&lon=%.5f", lat, lon)
        val a = JSONObject(get(url)).optJSONObject("address") ?: return null
        return listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k -> a.optString(k).ifBlank { null } }
    }

    /** Abre la navegación paso a paso en la app elegida (en el coche sale en Android Auto). */
    fun startNavigation(context: Context, app: NavApp, lat: Double, lon: Double) {
        val intent = when (app) {
            NavApp.INTERNAL -> return
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
