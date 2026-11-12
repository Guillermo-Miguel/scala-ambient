package es.scala.ambient.car

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import es.scala.ambient.MainActivity
import es.scala.ambient.R
import es.scala.ambient.ui.AppRoot
import es.scala.ambient.ui.CarDragTargets
import es.scala.ambient.ui.LocalCarMode
import androidx.compose.ui.geometry.Offset

/**
 * Dibuja la interfaz completa de la app (la misma del móvil) en la pantalla del coche.
 * Android Auto nos da una superficie; sobre ella se crea una pantalla virtual con la app dentro,
 * y los toques del coche se convierten en toques normales.
 */
class CarDisplay(private val car: CarContext) : SurfaceCallback {
    private var virtual: VirtualDisplay? = null
    private var screen: CarPresentation? = null
    private var width = 0
    private var height = 0
    private val main = Handler(Looper.getMainLooper())

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        width = container.width; height = container.height
        val v = virtual
        if (v != null) {
            // misma app, nuevo tamaño o superficie: se conserva la pantalla en la que estabas
            v.surface = surface
            v.resize(container.width, container.height, container.dpi)
            return
        }
        val dm = car.getSystemService(DisplayManager::class.java)
        val d = dm.createVirtualDisplay(
            "ScalaAmbientCar", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        virtual = d
        screen = CarPresentation(car, d.display).also { it.show() }
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        virtual?.surface = null
    }

    fun release() {
        finishDrag()
        runCatching { screen?.dismiss() }
        screen = null
        virtual?.release()
        virtual = null
    }

    // ---------- Toques del coche → toques de la app ----------

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val view = screen?.window?.decorView ?: return
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    override fun onClick(x: Float, y: Float) {
        finishDrag()
        val t = SystemClock.uptimeMillis()
        CarDragTargets.lastTap = Offset(x, y)
        CarDragTargets.lastTapAt = t
        touch(MotionEvent.ACTION_DOWN, x, y, t)
        main.postDelayed({ touch(MotionEvent.ACTION_UP, x, y, t) }, 60)
    }

    private var dragging = false
    private var dragTime = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val endDrag = Runnable { finishDrag() }

    /**
     * Arrastrar: Android Auto solo dice cuánto se ha movido el dedo, no dónde está.
     * El gesto se aplica sobre la lista o el mapa que hay en pantalla (o el último que tocaste).
     */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!dragging) {
            dragTime = SystemClock.uptimeMillis()
            val start = CarDragTargets.pick(distanceX, distanceY, dragTime) ?: Offset(width / 2f, height / 2f)
            dragging = true
            dragX = start.x; dragY = start.y
            touch(MotionEvent.ACTION_DOWN, dragX, dragY, dragTime)
        }
        dragX -= distanceX; dragY -= distanceY
        touch(MotionEvent.ACTION_MOVE, dragX, dragY, dragTime)
        main.removeCallbacks(endDrag)
        main.postDelayed(endDrag, 160)
    }

    override fun onFling(velocityX: Float, velocityY: Float) = finishDrag()

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        CarBus.zoom.tryEmit(scaleFactor)
    }

    private fun finishDrag() {
        main.removeCallbacks(endDrag)
        if (!dragging) return
        dragging = false
        touch(MotionEvent.ACTION_UP, dragX, dragY, dragTime)
    }
}

/** La app entera dentro de la pantalla virtual del coche. */
private class CarPresentation(ctx: Context, display: Display) :
    Presentation(ctx, display, R.style.Theme_ScalaAmbient) {

    private val owner = CarOwner(ctx)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        owner.create()
        window!!.addFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val decor = window!!.decorView
        decor.setViewTreeLifecycleOwner(owner)
        decor.setViewTreeSavedStateRegistryOwner(owner)
        decor.setViewTreeOnBackPressedDispatcherOwner(owner)
        val view = ComposeView(context).apply {
            setContent {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides owner,
                    LocalOnBackPressedDispatcherOwner provides owner,
                ) { AppRoot(carMode = true) }
            }
        }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        owner.resume()
    }

    override fun onStop() {
        owner.destroy()
        super.onStop()
    }
}

/** Ciclo de vida para Compose fuera de una Activity. Los permisos se piden abriendo la app en el móvil. */
private class CarOwner(private val ctx: Context) :
    LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
        ) {
            // En el coche no se pueden mostrar avisos de permisos: se abre la app en el móvil para darlos allí.
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun create() {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }
}
