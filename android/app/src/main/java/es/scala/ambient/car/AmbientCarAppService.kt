package es.scala.ambient.car

import android.content.Intent
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
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import es.scala.ambient.AmbientApp
import es.scala.ambient.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Mensajes de Android Auto hacia la interfaz. */
object CarBus {
    /** Se incrementa al pulsar «Inicio» en la barra de Android Auto. */
    val home = MutableStateFlow(0)
    /** Pellizco o doble toque sobre el mapa. */
    val zoom = MutableSharedFlow<Float>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

/**
 * Entrada de Android Auto. Se declara como app de navegación porque es la única forma
 * de que Android Auto deje dibujar una interfaz propia: así se ve la misma app que en el móvil.
 */
class AmbientCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarSession()
}

private class CarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val app = AmbientApp.instance
        app.carActive = true
        app.ble.start()
        app.location.start()
        app.media.start()

        val display = CarDisplay(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(display)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                display.release()
                app.carActive = false
            }
        })
        return MirrorScreen(carContext)
    }
}

/** Plantilla vacía de navegación: toda la pantalla es nuestra interfaz. */
private class MirrorScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val home = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_home)).build())
            .setOnClickListener { CarBus.home.value++ }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(home).build())
            // necesario para recibir los toques; en pantallas táctiles el botón no se muestra
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .setPanModeListener { }
            .build()
    }
}
