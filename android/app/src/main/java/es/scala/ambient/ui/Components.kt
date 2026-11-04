package es.scala.ambient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Calendar

/** Cabecera de cada pantalla: logo + título, y algo a la derecha. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandLogo(13.dp)
                LightMark()
            }
            Text(title, color = Ink, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp))
        }
        trailing()
    }
}

/** Botón de pastilla (primario en rojo o secundario de cristal). */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.height(48.dp).clip(shape)
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (primary) Red else Color.White.copy(alpha = 0.08f))
            .then(if (primary) Modifier else Modifier.border(1.dp, GlassEdge, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Botón redondo con icono. */
@Composable
fun RoundIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 48,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(50))
            .background(if (filled) Ink else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label,
            tint = when { filled -> Ground; enabled -> Ink; else -> Faint },
            modifier = Modifier.size((size * 0.42f).dp),
        )
    }
}

/** Tarjeta que explica un permiso que falta, con el botón para darlo. */
@Composable
fun PermissionCard(title: String, body: String, button: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Glass(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = Quiet, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(4.dp))
            PillButton(button, primary = true, onClick = onClick)
        }
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Quiet, fontSize = 13.sp, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Hora actual, refrescada cada pocos segundos. */
@Composable
fun rememberNow(): Calendar {
    val now by produceState(Calendar.getInstance()) {
        while (true) { delay(5_000); value = Calendar.getInstance() }
    }
    return now
}

fun two(n: Int) = if (n < 10) "0$n" else "$n"

fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${two((s % 60).toInt())}"
}
