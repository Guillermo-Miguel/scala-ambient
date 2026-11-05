package es.scala.ambient

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Un lugar guardado (Casa, Trabajo o el último destino). */
data class Place(val name: String, val address: String, val lat: Double, val lon: Double) {
    fun encode() = listOf(name, address, lat.toString(), lon.toString()).joinToString("\u001F")

    companion object {
        fun decode(s: String?): Place? = runCatching {
            val p = s!!.split("\u001F")
            Place(p[0], p[1], p[2].toDouble(), p[3].toDouble())
        }.getOrNull()
    }
}

enum class NavApp(val label: String) { INTERNAL("Integrado"), GOOGLE_MAPS("Google Maps"), WAZE("Waze") }

data class Settings(
    val driverName: String = "",
    val home: Place? = null,
    val work: Place? = null,
    val recent: Place? = null,
    val navApp: NavApp = NavApp.INTERNAL,
    val showWelcome: Boolean = true,
    val glassBlur: Boolean = true,
    /** Ajuste fino del tamaño de la interfaz (1 = automático). */
    val uiScale: Float = 1f,
    /** Abrir la app al conectarse a este Bluetooth (el del coche). */
    val carBluetooth: PairedDevice? = null,
    /** Abrir la app al encender el dispositivo. */
    val openOnBoot: Boolean = false,
)

/** Ajustes guardados en el móvil. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ambient", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load() = Settings(
        driverName = sp.getString("name", "") ?: "",
        home = Place.decode(sp.getString("home", null)),
        work = Place.decode(sp.getString("work", null)),
        recent = Place.decode(sp.getString("recent", null)),
        navApp = runCatching { NavApp.valueOf(sp.getString("navApp2", NavApp.INTERNAL.name)!!) }
            .getOrDefault(NavApp.INTERNAL),
        showWelcome = sp.getBoolean("welcome", true),
        glassBlur = sp.getBoolean("blur", true),
        uiScale = sp.getFloat("uiScale", 1f),
        carBluetooth = sp.getString("carBtAddr", null)?.let { PairedDevice(sp.getString("carBtName", it) ?: it, it) },
        openOnBoot = sp.getBoolean("openOnBoot", false),
    )

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        sp.edit()
            .putString("name", s.driverName)
            .putString("home", s.home?.encode())
            .putString("work", s.work?.encode())
            .putString("recent", s.recent?.encode())
            .putString("navApp2", s.navApp.name)
            .putBoolean("welcome", s.showWelcome)
            .putBoolean("blur", s.glassBlur)
            .putFloat("uiScale", s.uiScale)
            .putString("carBtAddr", s.carBluetooth?.address)
            .putString("carBtName", s.carBluetooth?.name)
            .putBoolean("openOnBoot", s.openOnBoot)
            .apply()
        _settings.value = s
    }
}
