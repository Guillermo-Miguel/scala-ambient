package es.scala.ambient

import android.app.Application
import es.scala.ambient.media.MediaRepo
import es.scala.ambient.nav.LocationRepo
import org.osmdroid.config.Configuration

class AmbientApp : Application() {
    lateinit var ble: AmbientBle
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var media: MediaRepo
        private set
    lateinit var location: LocationRepo
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        ble = AmbientBle(this)
        prefs = Prefs(this)
        media = MediaRepo(this)
        location = LocationRepo(this)

        // osmdroid: identificarse ante los servidores de mapas y guardar la caché en la app
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
    }

    companion object {
        lateinit var instance: AmbientApp
            private set
    }
}
