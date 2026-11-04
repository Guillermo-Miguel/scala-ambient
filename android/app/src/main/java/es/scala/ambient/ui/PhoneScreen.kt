package es.scala.ambient.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Favorite(val name: String, val number: String, val photo: ImageBitmap?)

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

/** Llama directamente si hay permiso; si no, abre el marcador con el número puesto. */
fun call(context: Context, number: String) {
    if (number.isBlank()) return
    val uri = Uri.parse("tel:" + Uri.encode(number))
    val action = if (granted(context, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
    context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private suspend fun loadFavorites(context: Context): List<Favorite> = withContext(Dispatchers.IO) {
    val out = LinkedHashMap<String, Favorite>()
    val cols = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
    )
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
        "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1", null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(0) ?: continue
            if (out.containsKey(name)) continue      // un número por contacto
            val number = c.getString(1) ?: continue
            val photo: Bitmap? = c.getString(2)?.let { u ->
                runCatching { context.contentResolver.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            out[name] = Favorite(name, number, photo?.asImageBitmap())
        }
    }
    out.values.toList()
}

@Composable
fun PhoneScreen(wide: Boolean) {
    val context = LocalContext.current
    var canRead by remember { mutableStateOf(granted(context, Manifest.permission.READ_CONTACTS)) }
    var favorites by remember { mutableStateOf<List<Favorite>?>(null) }
    var number by remember { mutableStateOf("") }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canRead = granted(context, Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(canRead) { if (canRead) favorites = loadFavorites(context) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Teléfono")

        val favs: @Composable (Modifier) -> Unit = { m ->
            Glass(m) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label("Favoritos")
                    when {
                        !canRead -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Muestra tus contactos favoritos para llamar con un toque.", color = Quiet, fontSize = 15.sp)
                            PillButton("Permitir contactos y llamadas", primary = true) {
                                ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
                            }
                        }
                        favorites == null -> Text("Cargando…", color = Quiet, fontSize = 15.sp)
                        favorites!!.isEmpty() -> Text("No tienes favoritos. Marca contactos con la estrella en la app de Contactos.", color = Quiet, fontSize = 15.sp)
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(120.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(favorites!!) { f -> FavoriteCard(f) { call(context, f.number) } }
                        }
                    }
                }
            }
        }

        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                favs(Modifier.weight(1f).fillMaxHeight())
                Glass(Modifier.width(380.dp).fillMaxHeight()) { Dialer(number, { number = it }) { call(context, number) } }
            }
        } else {
            Glass(Modifier.fillMaxWidth()) { Dialer(number, { number = it }) { call(context, number) } }
            favs(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun FavoriteCard(f: Favorite, onCall: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.04f))
            .clickable(role = Role.Button, onClickLabel = "Llamar a ${f.name}", onClick = onCall).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF1E1E22)), contentAlignment = Alignment.Center) {
            if (f.photo != null) Image(f.photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(f.name.take(1).uppercase(), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Text(f.name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Dialer(number: String, onChange: (String) -> Unit, onCall: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.ifEmpty { "Marcar número" }, color = if (number.isEmpty()) Faint else Ink,
                fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1, modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            if (number.isNotEmpty()) RoundIcon(Icons.Backspace, "Borrar", size = 44) { onChange(number.dropLast(1)) }
        }
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
                            .clickable(role = Role.Button, onClickLabel = k) { if (number.length < 20) onChange(number + k) },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Light) }
                }
            }
        }
        Box(
            Modifier.width(160.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (number.isEmpty()) Color.White.copy(alpha = 0.08f) else Red)
                .clickable(enabled = number.isNotEmpty(), role = Role.Button, onClickLabel = "Llamar", onClick = onCall),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Phone, "Llamar", tint = Color.White, modifier = Modifier.size(24.dp)) }
    }
}
