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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

/**
 * Zonas que se pueden arrastrar en el coche. Android Auto solo dice cuánto se mueve el dedo, no dónde está,
 * así que cada lista o mapa se apunta aquí y el gesto se aplica sobre la que toca.
 */
object CarDragTargets {
    class Target(val vertical: Boolean, val horizontal: Boolean) { var bounds: Rect = Rect.Zero }

    val list = ArrayList<Target>()
    var lastTap: Offset? = null
    var lastTapAt = 0L

    /** Punto donde empezar el arrastre: la zona que tocaste hace poco o, si no, la que está encima. */
    fun pick(dx: Float, dy: Float, now: Long): Offset? {
        val vertical = kotlin.math.abs(dy) >= kotlin.math.abs(dx)
        val candidates = list.filter { it.bounds.width > 8f && it.bounds.height > 8f && (if (vertical) it.vertical else it.horizontal) }
        if (candidates.isEmpty()) return null
        val tap = lastTap?.takeIf { now - lastTapAt < 15_000 }
        val t = tap?.let { p -> candidates.lastOrNull { it.bounds.contains(p) } } ?: candidates.last()
        return t.bounds.center
    }
}

/** Marca una lista (o el mapa) como arrastrable desde la pantalla del coche. */
@Composable
fun Modifier.carDrag(vertical: Boolean = true, horizontal: Boolean = false): Modifier {
    if (!LocalCarMode.current) return this
    val t = remember { CarDragTargets.Target(vertical, horizontal) }
    DisposableEffect(t) {
        CarDragTargets.list.add(t)
        onDispose { CarDragTargets.list.remove(t) }
    }
    return this.onGloballyPositioned { t.bounds = it.boundsInWindow() }
}

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
