package es.scala.ambient

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import es.scala.ambient.nav.NavModel
import es.scala.ambient.telecom.CallManager
import es.scala.ambient.telecom.CallService
import es.scala.ambient.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val app get() = AmbientApp.instance

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            app.ble.start()
            app.location.start()
            NavModel.refreshWeather()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Al primer arranque: Bluetooth (luz) y ubicación (mapa y tiempo).
        // Contactos y llamadas se piden al abrir Teléfono; la música, desde su pantalla.
        val needed = (AmbientBle.PERMISSIONS + arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )).distinct().toTypedArray()
        if (!app.ble.hasPermission() || !app.location.hasPermission()) askPermissions.launch(needed)

        handleIntent(intent)
        setContent { AppRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Llamadas: contestar desde la notificación, mostrarse con el móvil bloqueado, o marcar un número que pide otra app. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(CallService.EXTRA_SHOW_CALL, false)) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (intent.getBooleanExtra(CallService.EXTRA_ANSWER, false)) {
            intent.removeExtra(CallService.EXTRA_ANSWER)
            CallManager.answer()
        }
        val tel = intent.data?.takeIf { it.scheme == "tel" }
        if (intent.action == Intent.ACTION_DIAL || (intent.action == Intent.ACTION_VIEW && tel != null)) {
            CallManager.dialRequest.value = tel?.schemeSpecificPart ?: ""
            intent.action = Intent.ACTION_MAIN
            intent.data = null
        }
    }

    override fun onResume() {
        super.onResume()
        app.ble.start()
        app.location.start()
        app.media.start()
    }

    override fun onStop() {
        super.onStop()
        // la posición solo hace falta con la app a la vista (ahorra batería)
        if (!isChangingConfigurations) app.location.stop()
    }
}
