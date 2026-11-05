package es.scala.ambient.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import es.scala.ambient.AmbientApp
import es.scala.ambient.AmbientState
import es.scala.ambient.Cmd
import es.scala.ambient.Effect
import es.scala.ambient.PALETTE
import es.scala.ambient.R
import es.scala.ambient.Scene
import es.scala.ambient.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Las luces en Android Auto, presentadas como «app de música».
 *
 * Android Auto ya no muestra las apps de plantillas (domótica) instaladas fuera de Play Store,
 * pero sí las de música. Así que el control de luces se ofrece como:
 * - pestañas Escenas, Color, Efectos e Intensidad: tocar un elemento lo aplica;
 * - en el reproductor: Play/Pausa = encender/apagar, Siguiente/Anterior = escena, botón extra = efecto.
 * No reproduce ningún sonido.
 */
class LightsMediaService : MediaBrowserServiceCompat() {

    private val ble get() = AmbientApp.instance.ble
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat

    private companion object {
        const val ROOT = "root"
        const val BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val LIST = 1
        const val GRID = 2
        const val ACTION_NEXT_FX = "fx_next"
    }

    override fun onCreate() {
        super.onCreate()
        ble.start()
        session = MediaSessionCompat(this, "ScalaAmbientLights").apply {
            setCallback(callback)
            isActive = true
        }
        sessionToken = session.sessionToken
        scope.launch { ble.state.collect { publish(it) } }
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, Bundle().apply { putInt(BROWSABLE_HINT, LIST); putInt(PLAYABLE_HINT, LIST) })

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(children(parentId).toMutableList())
    }

    // ---------- Contenido ----------

    private fun folder(id: String, title: String, icon: Bitmap, playableStyle: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setIconBitmap(icon)
            .setExtras(Bundle().apply { putInt(PLAYABLE_HINT, playableStyle) }).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun item(id: String, title: String, subtitle: String?, icon: Bitmap) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).setIconBitmap(icon).build(),
        MediaItem.FLAG_PLAYABLE,
    )

    private fun children(parent: String): List<MediaItem> {
        val st = ble.state.value
        return when (parent) {
            ROOT -> listOf(
                folder("scenes", "Escenas", dots(0xF2F4F7, 0xE4002B), LIST),
                folder("colors", "Color", dots(0xFF9F0A, 0x2F7BFF), GRID),
                folder("effects", "Efectos", ring(0xF2F4F7), LIST),
                folder("bright", "Intensidad", bar(0.6f), LIST),
            )
            "scenes" -> listOf(
                item("power", if (st.on) "Apagar las luces" else "Encender las luces", null, dot(if (st.on) 0x8E8E93 else 0xF2F4F7)),
            ) + Scene.entries.map { s ->
                val (d, f) = when (s) {
                    Scene.FIRMA -> 0xF2F4F7 to 0xE4002B
                    Scene.CONFORT -> 0xFFC98A to 0xFFC98A
                    Scene.SPORT -> 0xE4002B to 0xE4002B
                    Scene.NOCHE -> 0xFF9F0A to 0xFF9F0A
                }
                item("scene:${s.code}", s.label, if (st.scene == s) "Activa" else null, dots(d, f))
            } + item("welcome", "Probar bienvenida", "Secuencia de encendido", ring(0xE4002B))
            "colors" -> PALETTE.map { sw ->
                item("color:%06X".format(sw.rgb), sw.name, if (st.zone(Zone.DASH).rgb == sw.rgb) "Actual" else null, dot(sw.rgb))
            }
            "effects" -> Effect.entries.map { e ->
                item("fx:${e.code}", e.label, if (st.effect == e) "Activo" else null, ring(if (st.effect == e) 0xE4002B else 0xF2F4F7))
            }
            "bright" -> listOf(10, 25, 50, 75, 100).map { v ->
                item("b:$v", "$v %", if (st.zone(Zone.DASH).bright == v) "Actual" else null, bar(v / 100f))
            }
            else -> emptyList()
        }
    }

    // ---------- Mandos ----------

    private fun nextScene(step: Int) {
        val all = Scene.entries
        val cur = ble.state.value.scene?.let { all.indexOf(it) } ?: -1
        val next = all[((cur + step) % all.size + all.size) % all.size]
        ble.send(Cmd.scene(next))
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = ble.send(Cmd.power(true))
        override fun onPause() = ble.send(Cmd.power(false))
        override fun onStop() = ble.send(Cmd.power(false))
        override fun onSkipToNext() = nextScene(+1)
        override fun onSkipToPrevious() = nextScene(-1)

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_NEXT_FX) {
                val all = Effect.entries
                ble.send(Cmd.effect(all[(all.indexOf(ble.state.value.effect) + 1) % all.size]))
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId ?: return
            val p = id.substringAfter(":", "")
            when {
                id == "power" -> ble.send(Cmd.power(!ble.state.value.on))
                id == "welcome" -> ble.send(Cmd.WELCOME)
                id.startsWith("scene:") -> Scene.of(p.toInt())?.let { ble.send(Cmd.scene(it)) }
                id.startsWith("color:") -> ble.send(Cmd.color(31, p.toInt(16)))
                id.startsWith("fx:") -> ble.send(Cmd.effect(Effect.of(p.toInt())))
                id.startsWith("b:") -> ble.send(Cmd.bright(31, p.toInt()))
            }
        }
    }

    /** Lo que se ve en el reproductor de Android Auto: estado de las luces. */
    private fun publish(st: AmbientState) {
        val dash = st.zone(Zone.DASH)
        val door = st.zone(Zone.FL)
        val link = ble.link.value
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, if (st.on) (st.scene?.label ?: "Personalizado") else "Luces apagadas")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, if (st.on) "${st.effect.label} · ${dash.bright} %" else link.label)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Luz ambiente · Scala")
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover(if (st.on) dash.rgb else 0x2A2A2D, if (st.on) door.rgb else 0x2A2A2D))
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                )
                .addCustomAction(ACTION_NEXT_FX, "Siguiente efecto", R.drawable.ic_bulb)
                .setState(
                    if (st.on) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f,
                )
                .build()
        )
        notifyChildrenChanged("scenes"); notifyChildrenChanged("colors")
        notifyChildrenChanged("effects"); notifyChildrenChanged("bright")
    }

    // ---------- Iconos dibujados ----------

    private fun canvas(size: Int = 128, draw: Canvas.(Paint) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(b).draw(Paint(Paint.ANTI_ALIAS_FLAG))
        return b
    }

    private fun argb(rgb: Int) = 0xFF000000.toInt() or rgb

    private fun dot(rgb: Int) = canvas { p -> p.color = argb(rgb); drawCircle(64f, 64f, 54f, p) }

    private fun dots(a: Int, b: Int) = canvas { p ->
        p.color = argb(a); drawCircle(42f, 64f, 30f, p)
        p.color = argb(b); drawCircle(86f, 64f, 30f, p)
    }

    private fun ring(rgb: Int) = canvas { p ->
        p.color = argb(rgb); p.style = Paint.Style.STROKE; p.strokeWidth = 12f; drawCircle(64f, 64f, 46f, p)
        p.style = Paint.Style.FILL; drawCircle(64f, 64f, 16f, p)
    }

    private fun bar(frac: Float) = canvas { p ->
        p.color = 0x55FFFFFF; drawRoundRect(14f, 54f, 114f, 74f, 10f, 10f, p)
        p.color = 0xFFF2F4F7.toInt(); drawRoundRect(14f, 54f, 14f + 100f * frac.coerceIn(0.08f, 1f), 74f, 10f, 10f, p)
    }

    /** Carátula: el coche resumido en una línea de luz del salpicadero y las puertas. */
    private fun cover(dash: Int, door: Int) = canvas(512) { p ->
        drawColor(0xFF050505.toInt())
        p.color = argb(door); p.alpha = 70; drawCircle(256f, 430f, 260f, p)
        p.alpha = 255
        p.color = argb(dash); drawRoundRect(96f, 236f, 416f, 252f, 8f, 8f, p)
        p.color = argb(door)
        drawRoundRect(70f, 180f, 86f, 330f, 8f, 8f, p)
        drawRoundRect(426f, 180f, 442f, 330f, 8f, 8f, p)
    }
}
