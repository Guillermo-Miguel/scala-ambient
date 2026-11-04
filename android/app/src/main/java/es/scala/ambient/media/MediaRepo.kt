package es.scala.ambient.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** Lo que está sonando ahora mismo en el móvil, venga de la app que venga. */
data class NowPlaying(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    val positionAt: Long,      // SystemClock.elapsedRealtime() del dato de posición
    val speed: Float,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
    val canSeek: Boolean,
) {
    /** Posición estimada en este instante. */
    fun positionNow(): Long {
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

class MediaRepo(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listenerName = ComponentName(context, MediaListener::class.java)

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now

    private val _sources = MutableStateFlow<List<String>>(emptyList())
    /** Apps con una sesión de audio abierta (para poder cambiar de fuente). */
    val sources: StateFlow<List<String>> = _sources

    private val _hasAccess = MutableStateFlow(false)
    val hasAccess: StateFlow<Boolean> = _hasAccess

    private var controller: MediaController? = null
    private var pinnedPackage: String? = null
    private var started = false
    private var artJob: Job? = null
    private var lastArtKey: String? = null
    private var lastArt: Bitmap? = null

    fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Ficha de la app: ahí está el menú ⋮ → «Permitir ajustes restringidos». */
    fun appInfoIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Llamar al abrir la app; no hace nada si falta el permiso. */
    fun start() {
        _hasAccess.value = hasNotificationAccess()
        if (!_hasAccess.value || started) { if (started) refresh(); return }
        try {
            msm.addOnActiveSessionsChangedListener({ list -> pick(list.orEmpty()) }, listenerName, main)
            started = true
            refresh()
        } catch (e: SecurityException) {
            _hasAccess.value = false
        }
    }

    fun refresh() {
        try { pick(msm.getActiveSessions(listenerName)) } catch (_: SecurityException) { _hasAccess.value = false }
    }

    /** Elegir a mano otra app (si hay varias con audio). */
    fun choose(packageName: String) {
        pinnedPackage = packageName
        refresh()
    }

    private fun pick(list: List<MediaController>) {
        _sources.value = list.map { it.packageName }.distinct()
        val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = list.firstOrNull { it.packageName == pinnedPackage && pinnedPackage != null }
            ?: playing
            ?: list.firstOrNull { it.packageName == controller?.packageName }
            ?: list.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, main)
        }
        if (playing != null && pinnedPackage != null && playing.packageName != pinnedPackage) pinnedPackage = null
        publish()
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { refresh() }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun publish() {
        val c = controller
        if (c == null) { _now.value = null; return }
        val md = c.metadata
        val st = c.playbackState
        val actions = st?.actions ?: 0L
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)

        val artKey = "${c.packageName}|$title|$artist|$album"
        val art = when {
            bitmap != null -> bitmap
            artKey == lastArtKey -> lastArt
            else -> null
        }
        if (bitmap == null && artUri != null && artKey != lastArtKey) loadArt(artUri, artKey)
        if (bitmap != null) { lastArtKey = artKey; lastArt = bitmap }

        _now.value = NowPlaying(
            packageName = c.packageName,
            appName = appLabel(c.packageName),
            title = title,
            artist = artist,
            album = album,
            art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionMs = st?.position ?: 0L,
            positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L || st == null,
            canSkipPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L || st == null,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
        )
    }

    /** Algunas apps solo dan la dirección de la carátula: la descargamos aparte. */
    private fun loadArt(uri: String, key: String) {
        artJob?.cancel()
        lastArtKey = key
        lastArt = null
        artJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val u = Uri.parse(uri)
                    val stream = if (u.scheme == "http" || u.scheme == "https") URL(uri).openStream()
                    else context.contentResolver.openInputStream(u)
                    stream?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (lastArtKey == key && bmp != null) { lastArt = bmp; publish() }
        }
    }

    // ---------- Mandos ----------

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { controller?.transportControls?.seekTo(ms) }

    /** Abre la app de música que está sonando. */
    fun openSourceApp() {
        val pkg = controller?.packageName ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
    }

    fun labelFor(pkg: String) = appLabel(pkg)
}
