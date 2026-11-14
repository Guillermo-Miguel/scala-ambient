package es.scala.ambient

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import com.github.takahirom.roborazzi.captureRoboImage
import es.scala.ambient.telecom.CallPhase
import es.scala.ambient.telecom.CallUi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import es.scala.ambient.ui.AppRoot
import es.scala.ambient.ui.BrandSection
import es.scala.ambient.ui.BuiltInLogo
import es.scala.ambient.ui.CommunityBrands
import es.scala.ambient.ui.Glass
import es.scala.ambient.ui.Ground
import es.scala.ambient.ui.CallScreen
import es.scala.ambient.ui.Dest
import es.scala.ambient.ui.LocalCarMode
import es.scala.ambient.ui.ScaledUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Capturas de la interfaz tal y como se dibuja en la pantalla del coche,
 * para comprobar tamaños y que nada se corta sin tener el coche delante.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CarShots {
    private fun shot(name: String, start: Dest, scale: Float = 1f) {
        AmbientApp.instance.prefs.update { it.copy(showWelcome = false, carScale = scale, driverName = "Guillermo") }
        captureRoboImage("build/shots/$name.png") {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppRoot(carMode = true, start = start) }
        }
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun home() = shot("800_home", Dest.HOME)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun nav() = shot("800_nav", Dest.NAV)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun music() = shot("800_music", Dest.MUSIC)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun phone() = shot("800_phone", Dest.PHONE)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun light() = shot("800_light", Dest.LIGHT)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun settings() = shot("800_settings", Dest.SETTINGS)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun homeBig() = shot("800_home_115", Dest.HOME, 1.15f)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun lightBig() = shot("800_light_115", Dest.LIGHT, 1.15f)
    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi") fun homeSmall() = shot("800_home_085", Dest.HOME, 0.85f)
    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun homeHd() = shot("1280_home", Dest.HOME)
    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun lightHd() = shot("1280_light", Dest.LIGHT)

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun brands() {
        captureRoboImage("build/shots/800_brands.png") {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCarMode provides true) {
                ScaledUi(1f, car = true) {
                    Column(
                        Modifier.fillMaxSize().background(Ground).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BuiltInLogo(Brands.AMBIENT, 13.dp)
                        BuiltInLogo(Brands.HALO, 13.dp)
                        BuiltInLogo(Brands.AMBIENT, 40.dp)
                        BuiltInLogo(Brands.HALO, 40.dp)
                        Glass(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { BrandSection { } }
                        }
                    }
                }
            }
        }
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun community() {
        captureRoboImage("build/shots/800_community.png") {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCarMode provides true) {
                ScaledUi(1f, car = true) {
                    Box(Modifier.fillMaxSize().background(Ground).padding(12.dp)) { CommunityBrands { } }
                }
            }
        }
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun ringing() {
        captureRoboImage("build/shots/800_ringing.png") {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCarMode provides true) {
                ScaledUi(1f, car = true) {
                    CallScreen(CallUi("", "Llamada entrante", CallPhase.RINGING, true, 0L, false, false, external = true))
                }
            }
        }
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun call() {
        captureRoboImage("build/shots/800_call.png") {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalCarMode provides true) {
                ScaledUi(1f, car = true) {
                    CallScreen(CallUi("612 345 678", "Marta", CallPhase.ACTIVE, false, 0L, false, false, external = true))
                }
            }
        }
    }
}
