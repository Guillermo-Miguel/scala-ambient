package es.scala.ambient.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import es.scala.ambient.AmbientApp

/**
 * Entrada de Android Auto. Categoría IOT: plantillas de Google (cuadrícula y listas).
 * Uso personal: se acepta cualquier anfitrión. Si algún día la publicas, usa una lista de anfitriones permitidos.
 */
class AmbientCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            AmbientApp.instance.ble.start()
            return HomeScreen(carContext)
        }
    }
}
