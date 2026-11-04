package es.scala.ambient.media

import android.service.notification.NotificationListenerService
import es.scala.ambient.AmbientApp

/**
 * Android solo deja leer lo que suena en otras apps a quien tiene "acceso a notificaciones".
 * Este servicio existe para obtener ese permiso; no lee ni guarda notificaciones.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AmbientApp.instance.media.start()
    }
}
