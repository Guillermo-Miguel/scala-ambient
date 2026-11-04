package es.scala.ambient.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Posición del móvil con el GPS de Android (sin depender de Google Play Services). */
@SuppressLint("MissingPermission")
class LocationRepo(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private var running = false

    // Con todos los métodos escritos: en Android 10 no tienen implementación por defecto
    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            val cur = _location.value
            // Preferir el GPS; aceptar la red solo si es más reciente o no hay otra
            if (cur == null || loc.provider == LocationManager.GPS_PROVIDER || loc.time - cur.time > 30_000) {
                _location.value = loc
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !hasPermission()) return
        running = true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { _location.value = it }
        providers.forEach { p ->
            runCatching {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 2_000L, 5f, listener, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
    }
}
