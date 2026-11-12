package es.scala.ambient.nav

import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import es.scala.ambient.AmbientApp
import es.scala.ambient.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.Locale

/** Lo que la pantalla de guía necesita en cada momento. */
data class NavState(
    val destination: Place,
    val route: Route,
    val remainingPoints: List<Pair<Double, Double>>,
    val step: Step?,                 // próxima maniobra
    val nextStep: Step?,             // la siguiente a esa
    val distToStep: Double,
    val remainingM: Double,
    val remainingS: Double,
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedKmh: Int,
    val road: String,                // por dónde vas ahora
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
    val muted: Boolean = false,
)

/**
 * Navegación paso a paso dentro de la app.
 * Sigue el GPS sobre la ruta, anuncia las maniobras por voz, recalcula si te desvías
 * y avisa al llegar. Sigue funcionando con la app en segundo plano (NavService).
 */
object NavSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state
    val active: Boolean get() = _state.value != null

    private var guided: GuidedRoute? = null
    private var seg = 0
    private var offCount = 0
    private var lastReroute = 0L
    private var spoken = HashSet<String>()
    private var smoothBearing = 0f
    private var muted = false
    private var locJob: Job? = null
    private var rerouteJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: AudioFocusRequest? = null

    // ---------- Empezar / terminar ----------

    fun start(context: Context, dest: Place, route: Route) {
        val app = context.applicationContext
        initTts(app)
        load(dest, route)
        AmbientApp.instance.location.setNavigating(true)
        // con la app solo en el coche Android puede no dejar arrancarlo: la guía sigue igual
        runCatching { ContextCompat.startForegroundService(app, Intent(app, NavService::class.java)) }
        locJob?.cancel()
        locJob = scope.launch {
            AmbientApp.instance.location.location.filterNotNull().collect { onLocation(it) }
        }
        val first = route.steps.getOrNull(1)
        say("Iniciando ruta a ${dest.name}." + (first?.let { " ${it.text}." } ?: ""), "start")
    }

    fun stop(context: Context) {
        locJob?.cancel(); rerouteJob?.cancel()
        _state.value = null
        guided = null
        AmbientApp.instance.location.setNavigating(false)
        runCatching { context.applicationContext.stopService(Intent(context.applicationContext, NavService::class.java)) }
        tts?.stop()
        abandonFocus()
    }

    fun toggleMute() {
        muted = !muted
        if (muted) tts?.stop()
        _state.value = _state.value?.copy(muted = muted)
    }

    private fun load(dest: Place, route: Route) {
        guided = GuidedRoute(route)
        seg = 0; offCount = 0; spoken = HashSet()
        val s = route.steps
        _state.value = NavState(
            destination = dest, route = route, remainingPoints = route.points,
            step = s.getOrNull(1) ?: s.firstOrNull(), nextStep = s.getOrNull(2),
            distToStep = s.firstOrNull()?.distance ?: 0.0,
            remainingM = route.meters, remainingS = route.seconds,
            lat = route.points.firstOrNull()?.first ?: dest.lat, lon = route.points.firstOrNull()?.second ?: dest.lon,
            bearing = smoothBearing, speedKmh = 0, road = s.firstOrNull()?.road ?: "",
            muted = muted,
        )
    }

    // ---------- Seguimiento ----------

    private fun onLocation(loc: Location) {
        val g = guided ?: return
        val cur = _state.value ?: return
        if (cur.arrived) return
        val p = loc.latitude to loc.longitude
        val pts = g.pts
        if (pts.size < 2) return

        // punto de la ruta más cercano, buscando cerca de donde íbamos
        var bestSeg = seg; var bestT = 0.0; var bestD = Double.MAX_VALUE
        val from = (seg - 3).coerceAtLeast(0)
        val to = (seg + 60).coerceAtMost(pts.size - 2)
        for (i in from..to) {
            val (t, d) = project(p, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestSeg = i; bestT = t }
        }
        seg = bestSeg
        val along = g.cum[bestSeg] + (g.cum[bestSeg + 1] - g.cum[bestSeg]) * bestT

        // rumbo suavizado: del GPS si nos movemos, si no, el de la ruta
        val raw = if (loc.hasBearing() && loc.speed > 2f) loc.bearing else bearing(pts[bestSeg], pts[bestSeg + 1])
        var diff = raw - smoothBearing
        if (diff > 180) diff -= 360; if (diff < -180) diff += 360
        smoothBearing = (smoothBearing + diff * 0.5f + 360) % 360

        // ¿desviado?
        val tolerance = maxOf(40.0, (if (loc.hasAccuracy()) loc.accuracy.toDouble() else 20.0) * 1.5)
        offCount = if (bestD > tolerance) offCount + 1 else 0
        if (offCount >= 3) { reroute(loc); return }

        // próxima maniobra
        val steps = g.route.steps
        if (steps.isEmpty()) return
        var k = steps.indices.firstOrNull { g.cum[g.stepIndex[it]] > along + 8 } ?: steps.lastIndex
        if (k < 1) k = minOf(1, steps.lastIndex)
        val step = steps.getOrNull(k)
        val distToStep = (g.cum[g.stepIndex[k]] - along).coerceAtLeast(0.0)
        val remaining = (g.total - along).coerceAtLeast(0.0)
        val remS = if (g.total > 0) g.route.seconds * remaining / g.total else 0.0
        val speedKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0

        val arrived = remaining < 25 || (step?.type == "arrive" && distToStep < 20)
        _state.value = cur.copy(
            remainingPoints = listOf(p) + pts.subList(bestSeg + 1, pts.size),
            step = step, nextStep = steps.getOrNull(k + 1),
            distToStep = distToStep, remainingM = remaining, remainingS = remS,
            lat = loc.latitude, lon = loc.longitude, bearing = smoothBearing, speedKmh = speedKmh,
            road = steps.getOrNull(k - 1)?.road ?: "",
            rerouting = false, arrived = arrived,
        )
        if (arrived) { say("Has llegado a tu destino.", "arrive"); return }
        if (step != null) announce(k, step, distToStep, loc.speed)
    }

    /** Avisos por voz: lejos, cerca y "ahora". */
    private fun announce(k: Int, step: Step, d: Double, speed: Float) {
        val v = speed.coerceAtLeast(8f)                  // m/s; mínimo como si fueras a ~30 km/h
        val far = (v * 30).coerceIn(400f, 1500f)
        val near = (v * 11).coerceIn(150f, 500f)
        val now = (v * 4).coerceIn(30f, 120f)
        when {
            d <= now && "$k-now" !in spoken -> { spoken += "$k-now"; spoken += "$k-near"; spoken += "$k-far"; say(step.text, "$k-now") }
            d <= near && "$k-near" !in spoken -> { spoken += "$k-near"; spoken += "$k-far"; say("En ${spokenDistance(d)}, ${step.text.replaceFirstChar { it.lowercase() }}", "$k-near") }
            d <= far && d > near * 1.6 && "$k-far" !in spoken -> { spoken += "$k-far"; say("En ${spokenDistance(d)}, ${step.text.replaceFirstChar { it.lowercase() }}", "$k-far") }
        }
    }

    private fun reroute(loc: Location) {
        val now = System.currentTimeMillis()
        if (now - lastReroute < 8_000 || rerouteJob?.isActive == true) return
        lastReroute = now
        offCount = 0
        val cur = _state.value ?: return
        _state.value = cur.copy(rerouting = true)
        say("Recalculando.", "reroute-$now")
        rerouteJob = scope.launch {
            val heading = if (loc.hasBearing() && loc.speed > 2f) loc.bearing else null
            runCatching { Net.route(loc.latitude to loc.longitude, cur.destination.lat to cur.destination.lon, heading) }
                .onSuccess { r ->
                    val muted0 = muted
                    load(cur.destination, r)
                    _state.value = _state.value?.copy(muted = muted0)
                    NavModel.setRoute(r)
                }
                .onFailure { _state.value = _state.value?.copy(rerouting = false) }
        }
    }

    // ---------- Voz ----------

    private fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setLanguage(Locale("es", "ES"))
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { abandonFocus() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { abandonFocus() }
                })
                pending?.let { (t, id) -> pending = null; say(t, id) }
            }
        }
    }

    private var pending: Pair<String, String>? = null

    private fun say(text: String, id: String) {
        if (muted) return
        val t = tts
        if (t == null || !ttsReady) { pending = text to id; return }
        requestFocus()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Baja la música mientras habla. */
    private fun requestFocus() {
        val am = AmbientApp.instance.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).build()
            focus = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() {
        val am = AmbientApp.instance.getSystemService(AudioManager::class.java)
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }
}
