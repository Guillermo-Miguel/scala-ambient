package es.scala.ambient.car

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import es.scala.ambient.AmbientApp
import es.scala.ambient.MainActivity
import es.scala.ambient.R
import es.scala.ambient.telecom.CallManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Mensajes de Android Auto hacia la interfaz. */
object CarBus {
    /** Pellizco o doble toque sobre el mapa. */
    val zoom = MutableSharedFlow<Float>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

/**
 * Entrada de Android Auto. Se declara como app de navegación porque es la única forma
 * de que Android Auto deje dibujar una interfaz propia: así se ve la misma app que en el móvil.
 */
class AmbientCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session {
        goForeground()
        return CarSession()
    }

    /**
     * Servicio en primer plano mientras la app está en el coche. Sin esto, con el móvil bloqueado
     * o la app cerrada en el móvil, Android no entrega la ubicación (permiso «solo mientras se usa»).
     */
    private fun goForeground() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "En el coche", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(
                this, 9, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_bulb)
                .setContentTitle("Scala Ambient")
                .setContentText("En la pantalla del coche")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, 6161, n, type)
        }
    }

    private companion object { const val CHANNEL = "car" }
}

private class CarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val app = AmbientApp.instance
        app.carActive = true
        app.ble.start()
        app.location.start()
        app.media.start()
        CallManager.watchSystemCalls(carContext)

        val display = CarDisplay(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(display)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                display.release()
                CallManager.stopWatchingSystemCalls()
                app.carActive = false
            }
        })
        return MirrorScreen(carContext)
    }
}

/** Plantilla vacía de navegación: toda la pantalla es nuestra interfaz. */
private class MirrorScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        // Android Auto obliga a poner al menos un botón: se deja uno vacío, sin dibujo ni función
        val blank = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_blank)).build())
            .setOnClickListener { }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(blank).build())
            // necesario para recibir los toques; en pantallas táctiles el botón no se muestra
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }
            .build()
    }
}
