package es.scala.ambient

/** Zonas en el mismo orden que el firmware: bit 0 = salpicadero … bit 4 = trasera derecha. */
enum class Zone(val label: String, val bit: Int) {
    DASH("Salpicadero", 1),
    FL("Puerta del. izq.", 2),
    FR("Puerta del. der.", 4),
    RL("Puerta tras. izq.", 8),
    RR("Puerta tras. der.", 16);
}

/** Grupos que se editan a la vez. */
enum class ZoneGroup(val label: String, val mask: Int, val lead: Zone) {
    ALL("Todo", 31, Zone.DASH),
    DASH("Salpicadero", 1, Zone.DASH),
    DOORS("Puertas", 30, Zone.FL),
    FL("Puerta del. izq.", 2, Zone.FL),
    FR("Puerta del. der.", 4, Zone.FR),
    RL("Puerta tras. izq.", 8, Zone.RL),
    RR("Puerta tras. der.", 16, Zone.RR);
}

data class Swatch(val name: String, val rgb: Int)

/** Misma paleta que el diseño. Ordenada para que en Android Auto salgan primero las más usadas. */
val PALETTE = listOf(
    Swatch("Blanco", 0xF2F4F7),
    Swatch("Rojo", 0xE4002B),
    Swatch("Cálido", 0xFFC98A),
    Swatch("Ámbar", 0xFF9F0A),
    Swatch("Azul", 0x2F7BFF),
    Swatch("Violeta", 0x9B6BFF),
    Swatch("Magenta", 0xFF2D92),
    Swatch("Cian", 0x33D1FF),
    Swatch("Turquesa", 0x2EE6B8),
    Swatch("Verde", 0x7BE36B),
)

/** Efectos de luz. `animated` = tiene velocidad ajustable. Mismos códigos que el firmware. */
enum class Effect(val label: String, val code: Int, val animated: Boolean) {
    FIXED("Fijo", 0, false),
    BREATHE("Respirar", 1, true),
    WAVE("Ola", 2, true),
    PULSE("Latido", 3, true),
    COMET("Cometa", 4, true),
    CANDLE("Vela", 5, true),
    RAINBOW("Arcoíris", 6, true),
    AURORA("Aurora", 7, true);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } ?: FIXED }
}

enum class Scene(val label: String, val code: Int, val iconRgb: Int) {
    FIRMA("Esencia", 1, 0xE4002B),
    CONFORT("Confort", 2, 0xFFC98A),
    SPORT("Monte Carlo", 3, 0xE4002B),
    NOCHE("Noche", 4, 0xFF9F0A);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

data class ZoneState(val rgb: Int, val bright: Int)

data class AmbientState(
    val on: Boolean = true,
    val effect: Effect = Effect.FIXED,
    val scene: Scene? = Scene.FIRMA,
    val speed: Int = 5,                 // 1 (lento) … 10 (rápido)
    val welcomeOnDoor: Boolean = true,  // bienvenida de luces al abrir la puerta
    val zones: List<ZoneState> = listOf(
        ZoneState(0xF2F4F7, 70), ZoneState(0xE4002B, 60), ZoneState(0xE4002B, 60),
        ZoneState(0xE4002B, 50), ZoneState(0xE4002B, 50),
    ),
) {
    fun zone(z: Zone) = zones[z.ordinal]

    fun swatchName(z: Zone): String =
        PALETTE.firstOrNull { it.rgb == zone(z).rgb }?.name ?: "Personalizado"

    /** Mismo formato que envía el firmware (para guardarlo en el móvil). */
    fun encode(): String = buildString {
        append(if (on) "1" else "0"); append(' ')
        append(effect.code); append(' ')
        append(scene?.code ?: 0); append(' ')
        append(speed); append(' ')
        append(if (welcomeOnDoor) "1" else "0")
        zones.forEach { append(" %06X%02X".format(it.rgb and 0xFFFFFF, it.bright.coerceIn(0, 100))) }
    }

    /** Órdenes que dejan la centralita exactamente así (al conectarla después de configurar sin ella). */
    fun commands(): List<String> = buildList {
        if (scene != null) add(Cmd.scene(scene))
        else zones.forEachIndexed { i, z ->
            add(Cmd.color(1 shl i, z.rgb)); add(Cmd.bright(1 shl i, z.bright))
        }
        add(Cmd.effect(effect))
        add(Cmd.speed(speed))
        add(Cmd.welcomeOnDoor(welcomeOnDoor))
        add(Cmd.power(on))
    }

    companion object {
        /**
         * Formato del firmware:
         * "<on> <fx> <scene> <speed> <welcome> RRGGBBbb ×5" (también acepta el antiguo sin speed/welcome).
         */
        fun parse(text: String): AmbientState? = runCatching {
            val p = text.trim().split(" ")
            val extended = p.size >= 10
            if (p.size < 8) return null
            val z0 = if (extended) 5 else 3
            val zones = (0 until 5).map { i ->
                val h = p[z0 + i]
                ZoneState(h.substring(0, 6).toInt(16), h.substring(6, 8).toInt(16))
            }
            AmbientState(
                on = p[0] == "1",
                effect = Effect.of(p[1].toInt()),
                scene = Scene.of(p[2].toInt()),
                speed = if (extended) p[3].toInt().coerceIn(1, 10) else 5,
                welcomeOnDoor = if (extended) p[4] == "1" else true,
                zones = zones,
            )
        }.getOrNull()
    }
}

/** Órdenes que entiende el firmware. */
object Cmd {
    fun power(on: Boolean) = if (on) "P1" else "P0"
    const val TOGGLE = "PT"
    fun color(mask: Int, rgb: Int) = "C $mask %06X".format(rgb and 0xFFFFFF)
    fun bright(mask: Int, value: Int) = "B $mask ${value.coerceIn(0, 100)}"
    fun effect(e: Effect) = "F ${e.code}"
    fun scene(s: Scene) = "S ${s.code}"
    fun speed(v: Int) = "V ${v.coerceIn(1, 10)}"
    fun welcomeOnDoor(on: Boolean) = if (on) "O1" else "O0"
    const val WELCOME = "W"
}
