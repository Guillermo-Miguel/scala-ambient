package com.calma.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("calma", Context.MODE_PRIVATE)

    /** Apps fijadas en la pantalla principal (en orden). */
    var favorites: List<String>
        get() = sp.getString("fav", "")!!.split("|").filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("fav", v.joinToString("|")).apply()

    /** Apps ocultas de la lista (siguen instaladas). */
    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet())!!)
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()

    /** Apps con "pausa consciente" antes de abrirse. */
    var distractors: Set<String>
        get() = HashSet(sp.getStringSet("distractors", emptySet())!!)
        set(v) = sp.edit().putStringSet("distractors", HashSet(v)).apply()

    var pauseSeconds: Int
        get() = sp.getInt("pause", 10)
        set(v) = sp.edit().putInt("pause", v).apply()

    private fun today(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun opensToday(pkg: String): Int = sp.getInt("c_${today()}_$pkg", 0)

    fun countOpen(pkg: String) {
        val key = "c_${today()}_$pkg"
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).apply()
    }

    /** Borra los contadores de días anteriores. */
    fun cleanOldCounts() {
        val prefix = "c_${today()}_"
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("c_") && !it.startsWith(prefix) }.forEach { e.remove(it) }
        e.apply()
    }
}
