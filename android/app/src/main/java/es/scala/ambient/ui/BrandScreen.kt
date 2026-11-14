package es.scala.ambient.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.scala.ambient.AmbientApp
import es.scala.ambient.Brands
import es.scala.ambient.CommunityBrand
import kotlinx.coroutines.launch

/** Ajustes → Marca de arranque: los dos logotipos propios y la entrada a Comunidad. */
@Composable
fun BrandSection(onCommunity: () -> Unit) {
    val context = LocalContext.current
    val s by AmbientApp.instance.prefs.settings.collectAsState()
    val community = Brands.isCommunity(s.brand)

    Text(
        "El logotipo que se ve en la bienvenida y arriba en cada pantalla.",
        color = Quiet, fontSize = 13.sp, lineHeight = 18.sp,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Brands.AMBIENT to "Ambient", Brands.HALO to "Halo").forEach { (id, label) ->
            val on = !community && (s.brand == id || (id == Brands.AMBIENT && s.brand != Brands.HALO))
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(if (on) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
                    .border(1.5.dp, if (on) Red else GlassEdge, shape)
                    .clickable(role = Role.RadioButton, onClickLabel = "Usar $label") { Brands.useBuiltIn(context, id) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
                    BuiltInLogo(id, 15.dp)
                }
                Text(label, color = if (on) Ink else Quiet, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Comunidad", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (community) "En uso: ${s.brandName}" else "Marcas compartidas por otros usuarios. Se descargan al elegirlas.",
                color = if (community) RedText else Quiet, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        PillButton("Ver marcas", primary = community, onClick = onCommunity)
    }
}

private class Preview(val data: ByteArray, val image: ImageBitmap)

/** Ajustes → Marca de arranque → Comunidad: marcas alojadas en GitHub, fuera de la app. */
@Composable
fun CommunityBrands(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by AmbientApp.instance.prefs.settings.collectAsState()
    var list by remember { mutableStateOf<List<CommunityBrand>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        list = null
        runCatching { Brands.catalog() }.onSuccess { list = it }.onFailure { failed = true }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Comunidad", trailing = { PillButton("Volver", onClick = onClose) })
        Column(
            Modifier.weight(1f).fillMaxWidth().carDrag().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val brands = list
            when {
                failed -> Glass(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No he podido cargar las marcas", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Comprueba la conexión a internet y vuelve a intentarlo.", color = Quiet, fontSize = 14.sp)
                        PillButton("Reintentar", primary = true) { attempt++ }
                    }
                }
                brands == null -> Text("Cargando marcas…", color = Quiet, fontSize = 15.sp, modifier = Modifier.padding(8.dp))
                brands.isEmpty() -> Text("Todavía no hay marcas compartidas.", color = Quiet, fontSize = 15.sp, modifier = Modifier.padding(8.dp))
                else -> brands.forEach { b ->
                    var preview by remember(b.id) { mutableStateOf<Preview?>(null) }
                    var error by remember(b.id) { mutableStateOf(false) }
                    LaunchedEffect(b.id) {
                        runCatching { Brands.download(b) }
                            .onSuccess { (data, image) -> preview = Preview(data, image) }
                            .onFailure { error = true }
                    }
                    val on = s.brand == "c:" + b.id
                    Glass(Modifier.fillMaxWidth().widthIn(max = 760.dp), padding = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(
                                Modifier.width(190.dp).height(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val p = preview
                                when {
                                    p != null -> Image(
                                        bitmap = p.image, contentDescription = b.name,
                                        colorFilter = if (b.mono) ColorFilter.tint(Ink) else null,
                                        modifier = Modifier.aspectRatio((p.image.width.toFloat() / p.image.height).coerceIn(0.5f, 12f)),
                                    )
                                    error -> Text("Sin imagen", color = Faint, fontSize = 12.sp)
                                    else -> Text("…", color = Faint, fontSize = 14.sp)
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (on) Text("En uso", color = RedText, fontSize = 13.sp)
                            }
                            PillButton(if (on) "En uso" else "Usar", primary = !on, enabled = !on && preview != null) {
                                val p = preview ?: return@PillButton
                                scope.launch { runCatching { Brands.use(context, b, p.data, p.image) } }
                            }
                        }
                    }
                }
            }
            Text(
                "Estas marcas no forman parte de la app: las comparten otros usuarios en GitHub y se descargan a tu móvil solo si las eliges. " +
                    "Cada marca y logotipo pertenece a su propietario.",
                color = Faint, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 760.dp),
            )
        }
    }
}
