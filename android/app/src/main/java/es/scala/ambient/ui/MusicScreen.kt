package es.scala.ambient.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.media.NowPlaying
import kotlinx.coroutines.delay

@Composable
fun MusicScreen(wide: Boolean) {
    val media = AmbientApp.instance.media
    val np by media.now.collectAsState()
    val access by media.hasAccess.collectAsState()
    val sources by media.sources.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { media.start() }

    Box(Modifier.fillMaxSize()) {
        // la carátula, muy desenfocada, de fondo
        val art = np?.art
        if (art != null && Build.VERSION.SDK_INT >= 31) {
            val img = remember(art) { art.asImageBitmap() }
            Crossfade(img, animationSpec = tween(600), label = "bg") { bmp ->
                Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(90.dp).alpha(0.35f))
            }
        }

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Música") {
                if (np != null) PillButton("Abrir ${np!!.appName}") { media.openSourceApp() }
            }

            if (!access) {
                PermissionCard(
                    "Permite ver lo que suena",
                    "Android solo deja leer la canción, la carátula y los mandos de otras apps (Spotify, YouTube Music, Amazon Music…) si das a esta app el acceso a notificaciones. No se leen ni se guardan tus notificaciones.",
                    "Abrir ajustes",
                    Modifier.widthIn(max = 560.dp),
                ) { context.startActivity(media.accessSettingsIntent()) }
                return@Column
            }

            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Crossfade(np?.title to np?.art, animationSpec = tween(400), modifier = Modifier.fillMaxHeight(0.92f).aspectRatio(1f), label = "art") {
                        AlbumArt(np, Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) { Player(np, sources) }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    AlbumArt(np, Modifier.fillMaxWidth().aspectRatio(1f))
                    Player(np, sources)
                }
            }
        }
    }
}

@Composable
private fun Player(np: NowPlaying?, sources: List<String>) {
    val media = AmbientApp.instance.media
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(if (np == null) "Nada sonando" else if (np.playing) "Reproduciendo" else "En pausa")
            Text(np?.title?.ifBlank { null } ?: "Pon música en el móvil", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 38.sp)
            Text(np?.artist ?: "", color = Quiet, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!np?.album.isNullOrBlank()) Text(np!!.album, color = Faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        SeekBar(np)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            RoundIcon(Icons.Prev, "Anterior", size = 60, enabled = np?.canSkipPrev == true) { media.previous() }
            RoundIcon(
                if (np?.playing == true) Icons.Pause else Icons.Play,
                if (np?.playing == true) "Pausar" else "Reproducir",
                size = 80, filled = true, enabled = np != null,
            ) { media.playPause() }
            RoundIcon(Icons.Next, "Siguiente", size = 60, enabled = np?.canSkipNext == true) { media.next() }
        }

        if (sources.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Fuente")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { pkg ->
                        val on = pkg == np?.packageName
                        val shape = RoundedCornerShape(20.dp)
                        Box(
                            Modifier.height(40.dp).clip(shape)
                                .background(if (on) Ink else Color.White.copy(alpha = 0.06f))
                                .border(1.dp, if (on) Ink else GlassEdge, shape)
                                .clickable(role = Role.Button) { media.choose(pkg) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(media.labelFor(pkg), color = if (on) Ground else Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
    }
}

/** Barra para ver y mover el punto de la canción. */
@Composable
private fun SeekBar(np: NowPlaying?) {
    val media = AmbientApp.instance.media
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np?.playing, np?.title) { while (np?.playing == true) { delay(500); tick++ } }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val dur = np?.durationMs ?: 0L
    val pos = if (tick < 0L) 0L else np?.positionNow() ?: 0L
    val frac = if (dur > 0) pos.toFloat() / dur else 0f

    Column {
        Slider(
            value = if (dragging) dragValue else frac,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                if (dur > 0) media.seekTo((dragValue * dur).toLong())
                dragging = false
            },
            enabled = np?.canSeek == true && dur > 0,
            colors = SliderDefaults.colors(
                thumbColor = Ink, activeTrackColor = Ink, inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                disabledThumbColor = Color.Transparent, disabledActiveTrackColor = Ink, disabledInactiveTrackColor = Color.White.copy(alpha = 0.14f),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatMs(if (dragging) (dragValue * dur).toLong() else pos), color = Quiet, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(if (dur > 0) formatMs(dur) else "", color = Quiet, fontSize = 13.sp)
        }
    }
}
