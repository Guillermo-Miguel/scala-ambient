package es.scala.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** true cuando la interfaz se dibuja en la pantalla del coche (Android Auto). */
val LocalCarMode = staticCompositionLocalOf { false }

/** Teclado propio para el coche: Android Auto no muestra el teclado del móvil. */
@Composable
fun CarKeyboard(
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKLÑ", "ZXCVBNM,.")
    Glass(modifier, radius = 24.dp, padding = PaddingValues(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.forEach { c -> Key(c.toString()) { onKey(c.lowercase()) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Key("Ocultar", weight = 1.6f, onClick = onClose)
                Key("espacio", weight = 4.4f) { onKey(" ") }
                Key("⌫", weight = 1.4f, onClick = onDelete)
                Key("Buscar", weight = 2f, primary = true, onClick = onSearch)
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, weight: Float = 1f, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.weight(weight).height(46.dp).clip(RoundedCornerShape(12.dp))
            .background(if (primary) Red else Color.White.copy(alpha = 0.09f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, fontSize = if (label.length > 1) 15.sp else 19.sp, fontWeight = FontWeight.Medium)
    }
}
