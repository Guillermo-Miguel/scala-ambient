package es.scala.ambient

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import es.scala.ambient.nav.NavModel
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

        setContent { AppRoot() }
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
