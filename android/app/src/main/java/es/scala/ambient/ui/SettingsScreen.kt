package es.scala.ambient.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import es.scala.ambient.AmbientApp
import es.scala.ambient.Cmd
import es.scala.ambient.Link
import es.scala.ambient.NavApp

@Composable
fun SettingsScreen(wide: Boolean, onReplayWelcome: () -> Unit) {
    val app = AmbientApp.instance
    val context = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val link by app.ble.link.collectAsState()
    val mediaAccess by app.media.hasAccess.collectAsState()
    var name by remember { mutableStateOf(s.driverName) }

    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun appSettings() = context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenHeader("Ajustes")
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Conductor") {
                Label("Nombre para el saludo")
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Tu nombre", color = Faint, fontSize = 17.sp)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(24); app.prefs.update { st -> st.copy(driverName = name.trim()) } },
                        singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 17.sp),
                        cursorBrush = SolidColor(Red),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ToggleRow("Animación de bienvenida al abrir la app", s.showWelcome) { v -> app.prefs.update { it.copy(showWelcome = v) } }
                PillButton("Ver la bienvenida ahora", onClick = onReplayWelcome)
            }

            Section("Navegación") {
                Label("Abrir la ruta en")
                Segmented(NavApp.entries.map { it.label }, s.navApp.ordinal, Modifier.fillMaxWidth()) { i ->
                    app.prefs.update { it.copy(navApp = NavApp.entries[i]) }
                }
                InfoRow("Casa", s.home?.address ?: "Sin guardar: búscala en Navegación y pulsa «Guardar como Casa»")
                InfoRow("Trabajo", s.work?.address ?: "Sin guardar")
            }

            Section("Luz ambiente") {
                InfoRow("Controlador", link.label)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Reconectar") { app.ble.start() }
                    PillButton("Probar bienvenida", enabled = link == Link.CONNECTED) { app.ble.send(Cmd.WELCOME) }
                }
            }

            Section("Permisos") {
                PermRow("Música (acceso a notificaciones)", mediaAccess) { context.startActivity(app.media.accessSettingsIntent()) }
                PermRow("Ubicación", has(Manifest.permission.ACCESS_FINE_LOCATION)) { appSettings() }
                PermRow("Contactos", has(Manifest.permission.READ_CONTACTS)) { appSettings() }
                PermRow("Llamadas", has(Manifest.permission.CALL_PHONE)) { appSettings() }
                PermRow("Bluetooth", app.ble.hasPermission()) { appSettings() }
            }

            Section("Acerca de") {
                Text(
                    "Mapa © OpenStreetMap y CARTO. Búsqueda: Nominatim. Rutas: OSRM. Tiempo: Open-Meteo.",
                    color = Quiet, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Glass(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(end = 16.dp))
        Text(value, color = Quiet, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = Color.White, uncheckedTrackColor = Color.White.copy(alpha = 0.15f)))
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) Ink else Red)
        Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (ok) Text("Permitido", color = Quiet, fontSize = 14.sp) else PillButton("Permitir", onClick = onFix)
    }
}
