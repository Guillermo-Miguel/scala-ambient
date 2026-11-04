package es.scala.ambient.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import es.scala.ambient.MainActivity
import es.scala.ambient.R

/**
 * Android nos entrega aquí cada llamada cuando Scala Ambient es la app de teléfono.
 * La interfaz la dibuja la propia app (pantalla de llamada); aquí solo se avisa y se gestiona el audio.
 */
class CallService : InCallService() {

    companion object {
        const val CHANNEL_RING = "calls_incoming"
        const val CHANNEL_ONGOING = "calls_ongoing"
        const val NOTIF_ID = 4242
        const val EXTRA_SHOW_CALL = "show_call"
        const val EXTRA_ANSWER = "answer_call"
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "Llamadas entrantes", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)          // el tono lo pone el sistema
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Llamada en curso", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.added(this, call)
        // Llamada que hacemos nosotros: traer la app al frente con la pantalla de llamada
        val ui = CallManager.ui.value
        if (ui != null && !ui.incoming) startActivity(openAppIntent())
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.removed(call)
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        @Suppress("DEPRECATION")
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) CallManager.audioChanged(audioState.isMuted, audioState.route, audioState.supportedRouteMask)
    }

    private fun openAppIntent() = Intent(this, MainActivity::class.java)
        .putExtra(EXTRA_SHOW_CALL, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun actionIntent(action: String, code: Int) = PendingIntent.getBroadcast(
        this, code, Intent(this, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Aviso de llamada entrante (pantalla completa si el móvil está bloqueado) o de llamada en curso. */
    @SuppressLint("MissingPermission")
    fun updateNotification(ui: CallUi?) {
        val nm = NotificationManagerCompat.from(this)
        if (ui == null || ui.phase == CallPhase.ENDED) { nm.cancel(NOTIF_ID); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33) return

        val open = PendingIntent.getActivity(this, 1, openAppIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ringing = ui.phase == CallPhase.RINGING && ui.incoming
        val b = NotificationCompat.Builder(this, if (ringing) CHANNEL_RING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle(ui.title)
            .setContentText(if (ringing) "Llamada entrante" else "Llamada en curso")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!ringing)
            .setContentIntent(open)
            .setColor(0xFFE4002B.toInt())
        if (ringing) {
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(open, true)
                .addAction(0, "Rechazar", actionIntent(CallActionReceiver.REJECT, 2))
                .addAction(
                    0, "Contestar",
                    // contestar abre la app (Android 12+ no deja abrirla desde un receptor)
                    PendingIntent.getActivity(this, 3, openAppIntent().putExtra(EXTRA_ANSWER, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
        } else {
            b.addAction(0, "Colgar", actionIntent(CallActionReceiver.HANG_UP, 4))
            if (ui.connectedAt > 0) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - ui.connectedAt))
        }
        nm.notify(NOTIF_ID, b.build())
    }
}

/** Botones de la notificación: rechazar y colgar. */
class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val REJECT = "es.scala.ambient.REJECT"
        const val HANG_UP = "es.scala.ambient.HANG_UP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            REJECT -> CallManager.reject()
            HANG_UP -> CallManager.hangUp()
        }
    }
}
