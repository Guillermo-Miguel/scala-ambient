package es.scala.ambient.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import es.scala.ambient.MainActivity
import es.scala.ambient.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantiene viva la navegación con la app en segundo plano o la pantalla apagada
 * y muestra la próxima maniobra en la barra de notificaciones.
 */
class NavService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting = false

    companion object {
        const val CHANNEL = "navigation"
        const val ID = 5151
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Navegación", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ID, build(NavSession.state.value), type)
        if (collecting) return START_NOT_STICKY
        collecting = true
        scope.launch {
            NavSession.state.collect { st ->
                if (st == null) { stopSelf(); return@collect }
                getSystemService(NotificationManager::class.java).notify(ID, build(st))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(st: NavState?): Notification {
        val open = PendingIntent.getActivity(
            this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            st == null -> "Navegando"
            st.arrived -> "Has llegado"
            st.rerouting -> "Recalculando…"
            else -> "${shortDistance(st.distToStep)} · ${st.step?.text ?: ""}"
        }
        val text = st?.let { "${formatDuration(it.remainingS)} · ${formatDistance(it.remainingM)} hasta ${it.destination.name}" } ?: ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .setColor(0xFFE4002B.toInt())
            .build()
    }
}
