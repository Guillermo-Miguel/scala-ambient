package es.scala.ambient.nav

import es.scala.ambient.AmbientApp
import es.scala.ambient.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Destino elegido y su ruta. Lo comparten Inicio y Navegación. */
object NavModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private var routeJob: Job? = null
    private var weatherJob: Job? = null

    private fun here(): Pair<Double, Double>? =
        AmbientApp.instance.location.location.value?.let { it.latitude to it.longitude }

    fun choose(place: Place) {
        _destination.value = place
        AmbientApp.instance.prefs.update { it.copy(recent = place) }
        recalc()
    }

    /** Ruta nueva tras recalcular en marcha (el destino no cambia). */
    fun setRoute(r: Route) {
        _route.value = r
        _error.value = null
    }

    fun clear() {
        routeJob?.cancel()
        _destination.value = null
        _route.value = null
        _error.value = null
    }

    fun recalc() {
        val dest = _destination.value ?: return
        routeJob?.cancel()
        routeJob = scope.launch {
            _busy.value = true
            _error.value = null
            // esperar unos segundos a tener posición
            var from = here()
            var tries = 0
            while (from == null && tries < 10) { delay(500); from = here(); tries++ }
            if (from == null) {
                _error.value = "Sin posición GPS"
            } else {
                runCatching { Net.route(from, dest.lat to dest.lon) }
                    .onSuccess { _route.value = it }
                    .onFailure { _error.value = "No se pudo calcular la ruta" }
            }
            _busy.value = false
        }
    }

    /** Tiempo exterior; se actualiza cada 15 minutos como mucho. */
    fun refreshWeather(force: Boolean = false) {
        if (weatherJob?.isActive == true && !force) return
        weatherJob?.cancel()
        weatherJob = scope.launch {
            while (true) {
                val p = here()
                if (p != null) runCatching { Net.weather(p.first, p.second) }.onSuccess { _weather.value = it }
                delay(if (p == null) 5_000 else 15 * 60_000L)
            }
        }
    }
}
