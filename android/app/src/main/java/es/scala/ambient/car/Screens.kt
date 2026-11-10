package es.scala.ambient.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import es.scala.ambient.AmbientApp
import es.scala.ambient.Cmd
import es.scala.ambient.Effect
import es.scala.ambient.Link
import es.scala.ambient.PALETTE
import es.scala.ambient.R
import es.scala.ambient.Scene
import es.scala.ambient.ZoneGroup
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private fun tint(rgb: Int): CarColor {
    val c = 0xFF000000.toInt() or rgb
    return CarColor.createCustom(c, c)
}

/** Base: se redibuja cada vez que cambia el estado del ESP32 o la conexión. */
abstract class AmbientScreen(ctx: CarContext) : Screen(ctx) {
    protected val ble = AmbientApp.instance.ble

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                lifecycleScope.launch { combine(ble.state, ble.link) { _, _ -> }.collect { invalidate() } }
            }
        })
    }

    protected fun icon(res: Int, rgb: Int? = null): CarIcon {
        val b = CarIcon.Builder(IconCompat.createWithResource(carContext, res))
        if (rgb != null) b.setTint(tint(rgb))
        return b.build()
    }

    /** Máximo de elementos que deja mostrar el coche (suele ser 6 en marcha). */
    protected fun gridLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
    }.getOrDefault(6)

    protected fun notReady(): Template? {
        val link = ble.link.value
        if (link == Link.CONNECTED) return null
        // Nunca un círculo de carga infinito: si la centralita no aparece, se explica y se puede reintentar.
        val msg = when (link) {
            Link.NO_PERMISSION -> "Abre Scala Ambient en el móvil y permite el Bluetooth."
            Link.BLUETOOTH_OFF -> "Activa el Bluetooth del móvil."
            Link.CONNECTING -> "Conectando con la centralita de luces…"
            else -> "Buscando la centralita de luces del Scala.\n" +
                "Se conectará sola en cuanto esté instalada y encendida."
        }
        return MessageTemplate.Builder(msg)
            .setTitle("Luz ambiente")
            .setHeaderAction(Action.APP_ICON)
            .setIcon(icon(R.drawable.ic_bulb, 0xE4002B))
            .addAction(
                Action.Builder()
                    .setTitle("Reintentar")
                    .setOnClickListener { ble.start(); invalidate() }
                    .build()
            )
            .build()
    }
}

/** Inicio: encendido, cuatro escenas y ajustes. */
class HomeScreen(ctx: CarContext) : AmbientScreen(ctx) {
    override fun onGetTemplate(): Template {
        notReady()?.let { return it }
        val s = ble.state.value
        val lead = s.zone(ZoneGroup.ALL.lead)
        val items = ItemList.Builder()

        items.addItem(
            GridItem.Builder()
                .setTitle(if (s.on) "Apagar" else "Encender")
                .setText(if (s.on) "${lead.bright} %" else "Apagada")
                .setImage(icon(R.drawable.ic_bulb, if (s.on) lead.rgb else 0x8E8E93))
                .setOnClickListener { ble.send(Cmd.power(!s.on)) }
                .build()
        )
        Scene.entries.forEach { sc ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(sc.label)
                    .apply { if (s.scene == sc) setText("Activa") }
                    .setImage(icon(R.drawable.ic_dot, sc.iconRgb))
                    .setOnClickListener { ble.send(Cmd.scene(sc)) }
                    .build()
            )
        }
        items.addItem(
            GridItem.Builder()
                .setTitle("Ajustes")
                .setText("Color · intensidad")
                .setImage(icon(R.drawable.ic_bulb))
                .setOnClickListener { screenManager.push(SettingsScreen(carContext)) }
                .build()
        )

        return GridTemplate.Builder()
            .setTitle("Luz ambiente · Scala")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }
}

/** Ajustes: zona, color, intensidad, efecto, bienvenida. */
class SettingsScreen(ctx: CarContext) : AmbientScreen(ctx) {
    private var group = ZoneGroup.ALL
    private val groups = listOf(ZoneGroup.ALL, ZoneGroup.DASH, ZoneGroup.DOORS)

    override fun onGetTemplate(): Template {
        notReady()?.let { return it }
        val s = ble.state.value
        val lead = s.zone(group.lead)
        val list = ItemList.Builder()

        list.addItem(
            Row.Builder().setTitle("Zona: ${group.label}").addText("Toca para cambiar")
                .setOnClickListener { group = groups[(groups.indexOf(group) + 1) % groups.size]; invalidate() }
                .build()
        )
        list.addItem(
            Row.Builder().setTitle("Color").addText(s.swatchName(group.lead))
                .setImage(icon(R.drawable.ic_dot, lead.rgb))
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(ColorScreen(carContext, group)) }
                .build()
        )
        list.addItem(
            Row.Builder().setTitle("Intensidad").addText("${lead.bright} %")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(BrightnessScreen(carContext, group)) }
                .build()
        )
        list.addItem(
            Row.Builder().setTitle("Efecto: ${s.effect.label}").addText("Toca para cambiar")
                .setOnClickListener {
                    val next = Effect.entries[(s.effect.ordinal + 1) % Effect.entries.size]
                    ble.send(Cmd.effect(next))
                }
                .build()
        )
        list.addItem(
            Row.Builder().setTitle("Probar bienvenida")
                .setOnClickListener { ble.send(Cmd.WELCOME) }
                .build()
        )

        return ListTemplate.Builder()
            .setTitle("Ajustes")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

class ColorScreen(ctx: CarContext, private val group: ZoneGroup) : AmbientScreen(ctx) {
    override fun onGetTemplate(): Template {
        notReady()?.let { return it }
        val current = ble.state.value.zone(group.lead).rgb
        val items = ItemList.Builder()
        PALETTE.take(gridLimit()).forEach { sw ->
            items.addItem(
                GridItem.Builder()
                    .setTitle(sw.name)
                    .apply { if (sw.rgb == current) setText("Elegido") }
                    .setImage(icon(R.drawable.ic_dot, sw.rgb))
                    .setOnClickListener { ble.send(Cmd.color(group.mask, sw.rgb)); screenManager.pop() }
                    .build()
            )
        }
        return GridTemplate.Builder()
            .setTitle("Color · ${group.label}")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}

class BrightnessScreen(ctx: CarContext, private val group: ZoneGroup) : AmbientScreen(ctx) {
    private val steps = listOf(10, 25, 50, 75, 100)

    override fun onGetTemplate(): Template {
        notReady()?.let { return it }
        val current = ble.state.value.zone(group.lead).bright
        val items = ItemList.Builder()
        steps.forEach { v ->
            items.addItem(
                GridItem.Builder()
                    .setTitle("$v %")
                    .apply { if (v == current) setText("Actual") }
                    .setImage(icon(R.drawable.ic_bulb, 0xFFFFFF))
                    .setOnClickListener { ble.send(Cmd.bright(group.mask, v)); screenManager.pop() }
                    .build()
            )
        }
        return GridTemplate.Builder()
            .setTitle("Intensidad · ${group.label}")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
