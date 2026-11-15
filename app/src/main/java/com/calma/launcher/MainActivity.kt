package com.calma.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class AppEntry(val label: String, val pkg: String, val component: ComponentName)

class MainActivity : Activity() {

    // Paleta: negro puro + grises. Nada de color que llame la atención.
    private val BG = Color.BLACK
    private val FG = 0xFFEDEDED.toInt()
    private val DIM = 0xFF77777C.toInt()
    private val FAINT = 0xFF2E2E31.toInt()

    private val MAX_FAVS = 6

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var favBox: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var adapter: AppAdapter
    private lateinit var gestures: GestureDetector

    private var allApps: List<AppEntry> = emptyList()   // todas (incl. ocultas)
    private var visibleApps: List<AppEntry> = emptyList() // sin ocultas
    private var drawerOpen = false
    private var downAtListTop = false
    private var pauseOverlay: View? = null
    private var pauseTick: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = updateClock()
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = loadApps()
    }

    // ───────────────────────── ciclo de vida ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.cleanOldCounts()
        buildUi()
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx) * 1.5f || abs(dy) < dp(80)) return false
                if (pauseOverlay != null) return false
                if (!drawerOpen && dy < 0) openDrawer()
                else if (drawerOpen && dy > 0 && downAtListTop) closeDrawer()
                return false
            }
        })

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
        pauseTick?.let { handler.removeCallbacks(it) }
    }

    /** Pulsar Home estando dentro del launcher: volver siempre a la pantalla limpia. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dismissPause()
        closeDrawer(animate = false)
        updateClock()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            pauseOverlay != null -> dismissPause()
            drawerOpen -> closeDrawer()
            else -> { /* somos el launcher: no hay a dónde volver */ }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downAtListTop = listAtTop()
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ───────────────────────── interfaz ─────────────────────────

    private fun tv(text: CharSequence, sp: Float, color: Int, face: Typeface): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(BG) }

        // ── Pantalla principal ──
        val home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(72), dp(36), dp(40))
        }

        clockView = tv("", 76f, FG, thin).apply {
            letterSpacing = -0.02f
            setOnClickListener {
                runCatching {
                    startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            setOnLongClickListener { showSettingsMenu(); true }
        }
        dateView = tv("", 15f, DIM, light).apply {
            letterSpacing = 0.04f
            setPadding(dp(3), dp(10), 0, 0)
        }
        home.addView(clockView)
        home.addView(dateView)

        home.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f)) // espaciador

        favBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(favBox)

        // Barrita discreta que insinúa "desliza hacia arriba"
        val handle = View(this).apply {
            background = GradientDrawable().apply {
                setColor(FAINT)
                cornerRadius = dp(2).toFloat()
            }
        }
        home.addView(handle, LinearLayout.LayoutParams(dp(36), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(36)
        })
        root.addView(home, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // ── Cajón de apps (solo texto, sin iconos) ──
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(36), dp(40), dp(36), 0)
            visibility = View.GONE
            isClickable = true
        }
        search = EditText(this).apply {
            hint = "Buscar"
            setHintTextColor(DIM)
            setTextColor(FG)
            typeface = light
            textSize = 20f
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, dp(12), 0, dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                this@MainActivity.adapter.items.firstOrNull()?.let { launch(it) }
                true
            }
        }
        drawer.addView(search)
        drawer.addView(View(this).apply { setBackgroundColor(FAINT) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        adapter = AppAdapter()
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(8), 0, dp(24))
            clipToPadding = false
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> launch(this@MainActivity.adapter.items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showAppOptions(this@MainActivity.adapter.items[pos]); true }
        }
        drawer.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(drawer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun updateClock() {
        val now = Date()
        val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clockView.text = DateFormat.format(pattern, now)
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        dateView.text = DateFormat.format(datePattern, now).toString().lowercase(Locale.getDefault())
    }

    private fun refreshFavorites() {
        favBox.removeAllViews()
        val byPkg = visibleApps.associateBy { it.pkg }
        val favs = prefs.favorites.mapNotNull { byPkg[it] }
        if (favs.isEmpty()) {
            favBox.addView(tv("Desliza hacia arriba para ver tus apps.\nMantén pulsada una para fijarla aquí.",
                13f, DIM, light).apply { setLineSpacing(0f, 1.25f) })
            return
        }
        favs.forEach { app ->
            favBox.addView(tv(app.label, 26f, FG, light).apply {
                letterSpacing = 0.01f
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { launch(app) }
                setOnLongClickListener { showAppOptions(app); true }
            })
        }
    }

    // ───────────────────────── cajón ─────────────────────────

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        search.setText("")
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        drawer.translationY = dp(60).toFloat()
        drawer.animate().alpha(1f).translationY(0f).setDuration(180).start()
        list.setSelection(0)
    }

    private fun closeDrawer(animate: Boolean = true) {
        if (!drawerOpen) return
        drawerOpen = false
        hideKeyboard()
        search.clearFocus()
        if (animate) {
            drawer.animate().alpha(0f).translationY(dp(60).toFloat()).setDuration(150)
                .withEndAction { if (!drawerOpen) drawer.visibility = View.GONE }.start()
        } else {
            drawer.animate().cancel()
            drawer.visibility = View.GONE
        }
    }

    private fun listAtTop(): Boolean {
        if (!drawerOpen) return false
        if (list.childCount == 0) return true
        return list.firstVisiblePosition == 0 && list.getChildAt(0).top >= list.paddingTop
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun applyFilter() {
        val q = normalize(search.text.toString().trim())
        adapter.items = if (q.isEmpty()) visibleApps else visibleApps.filter { normalize(it.label).contains(q) }
        adapter.notifyDataSetChanged()
    }

    // ───────────────────────── apps ─────────────────────────

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val result = pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    )
                }
                .distinctBy { it.pkg }
                .sortedBy { normalize(it.label) }
            runOnUiThread {
                allApps = result
                refreshAll()
            }
        }.start()
    }

    private fun refreshAll() {
        val hidden = prefs.hidden
        visibleApps = allApps.filter { it.pkg !in hidden }
        applyFilter()
        refreshFavorites()
    }

    private fun launch(app: AppEntry) {
        if (app.pkg in prefs.distractors) showPause(app) else start(app)
    }

    private fun start(app: AppEntry) {
        prefs.countOpen(app.pkg)
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = app.component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            startActivity(intent)
            closeDrawer(animate = false)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────── pausa consciente ─────────────────────────

    private fun showPause(app: AppEntry) {
        hideKeyboard()
        val total = prefs.pauseSeconds
        val opens = prefs.opensToday(app.pkg)

        val ov = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG)
            setPadding(dp(40), dp(40), dp(40), dp(40))
            isClickable = true
            alpha = 0f
        }
        ov.addView(tv(app.label, 15f, DIM, light).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        })
        val count = tv("$total", 84f, FG, thin).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(20))
        }
        ov.addView(count)
        ov.addView(tv("Respira. ¿De verdad lo necesitas ahora?", 16f, FG, light).apply {
            gravity = Gravity.CENTER
        })
        if (opens > 0) {
            ov.addView(tv(
                if (opens == 1) "Hoy ya la abriste 1 vez." else "Hoy ya la abriste $opens veces.",
                13f, DIM, light
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(56), 0, 0)
        }
        val cancel = tv("Mejor no", 18f, FG, light).apply {
            setPadding(dp(24), dp(14), dp(24), dp(14))
            setOnClickListener { dismissPause() }
        }
        val open = tv("Abrir", 18f, FAINT, light).apply {
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isEnabled = false
            setOnClickListener {
                dismissPause()
                start(app)
            }
        }
        buttons.addView(cancel)
        buttons.addView(open)
        ov.addView(buttons)

        root.addView(ov, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ov.animate().alpha(1f).setDuration(250).start()
        pauseOverlay = ov

        var left = total
        val tick = object : Runnable {
            override fun run() {
                left--
                if (left <= 0) {
                    count.textSize = 22f
                    count.text = "Tú decides."
                    open.setTextColor(FG)
                    open.isEnabled = true
                } else {
                    count.text = "$left"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        pauseTick = tick
        if (total > 0) handler.postDelayed(tick, 1000) else tick.run()
    }

    private fun dismissPause() {
        pauseTick?.let { handler.removeCallbacks(it) }
        pauseTick = null
        pauseOverlay?.let { root.removeView(it) }
        pauseOverlay = null
    }

    // ───────────────────────── menús ─────────────────────────

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showAppOptions(app: AppEntry) {
        val favs = prefs.favorites
        val isFav = app.pkg in favs
        val isDistractor = app.pkg in prefs.distractors

        val labels = arrayOf(
            if (isFav) "Quitar de la pantalla principal" else "Fijar en la pantalla principal",
            if (isDistractor) "Quitar pausa consciente" else "Añadir pausa consciente",
            "Ocultar",
            "Información de la app",
            "Desinstalar"
        )
        dialog().setTitle(app.label).setItems(labels) { _, which ->
            when (which) {
                0 -> {
                    if (isFav) prefs.favorites = favs - app.pkg
                    else if (favs.size >= MAX_FAVS)
                        Toast.makeText(this, "Máximo $MAX_FAVS apps fijadas", Toast.LENGTH_SHORT).show()
                    else prefs.favorites = favs + app.pkg
                    refreshFavorites()
                }
                1 -> {
                    prefs.distractors = if (isDistractor) prefs.distractors - app.pkg else prefs.distractors + app.pkg
                    adapter.notifyDataSetChanged()
                }
                2 -> {
                    prefs.hidden = prefs.hidden + app.pkg
                    refreshAll()
                    Toast.makeText(this, "Oculta. Puedes recuperarla manteniendo pulsada la hora.", Toast.LENGTH_LONG).show()
                }
                3 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${app.pkg}")))
                4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
        }.show()
    }

    private fun showSettingsMenu() {
        val labels = arrayOf(
            "Apps ocultas…",
            "Duración de la pausa (${prefs.pauseSeconds} s)…",
            "Elegir launcher predeterminado",
            "Ajustes del sistema"
        )
        dialog().setTitle("Calma").setItems(labels) { _, which ->
            when (which) {
                0 -> showHiddenPicker()
                1 -> showPausePicker()
                2 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                3 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }.show()
    }

    private fun showHiddenPicker() {
        val hidden = prefs.hidden.toMutableSet()
        val labels = allApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(allApps.size) { allApps[it].pkg in hidden }
        dialog().setTitle("Apps ocultas")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked ->
                if (isChecked) hidden.add(allApps[i].pkg) else hidden.remove(allApps[i].pkg)
            }
            .setPositiveButton("Listo") { _, _ ->
                prefs.hidden = hidden
                // Si algo oculto estaba fijado, deja de mostrarse en la home automáticamente.
                refreshAll()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPausePicker() {
        val options = intArrayOf(5, 10, 15, 30, 60)
        val labels = options.map { "$it segundos" }.toTypedArray()
        dialog().setTitle("Duración de la pausa")
            .setSingleChoiceItems(labels, options.indexOf(prefs.pauseSeconds)) { d, i ->
                prefs.pauseSeconds = options[i]
                d.dismiss()
            }.show()
    }

    // ───────────────────────── adapter ─────────────────────────

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val app = items[position]
            val view = (convertView as? TextView) ?: tv("", 20f, FG, light).apply {
                letterSpacing = 0.01f
                setPadding(0, dp(13), 0, dp(13))
            }
            if (app.pkg in prefs.distractors) {
                // Punto tenue junto al nombre: esta app tiene pausa consciente.
                val s = SpannableString("${app.label}  ·")
                s.setSpan(ForegroundColorSpan(DIM), app.label.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                view.text = s
            } else {
                view.text = app.label
            }
            return view
        }
    }
}
