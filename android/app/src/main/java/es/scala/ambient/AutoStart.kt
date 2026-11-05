package es.scala.ambient

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Un dispositivo Bluetooth emparejado (para elegir el del coche). */
data class PairedDevice(val name: String, val address: String)

/**
 * Abre la app sola:
 * - al conectarse el móvil al Bluetooth del coche (el que elijas en Ajustes),
 * - o al encender el dispositivo (útil en una pantalla fija en el coche).
 * Android solo deja abrir una app desde segundo plano si tiene el permiso
 * «Mostrar sobre otras apps»; sin él, se muestra un aviso para abrirla con un toque.
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context).settings.value
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val address = device?.address ?: return
                if (prefs.carBluetooth != null && address.equals(prefs.carBluetooth.address, ignoreCase = true)) {
                    AutoStart.launch(context, "Conectado a ${prefs.carBluetooth.name}")
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                if (prefs.openOnBoot) AutoStart.launch(context, "Dispositivo encendido")
            }
        }
    }
}

object AutoStart {
    private const val CHANNEL = "autostart"
    private const val ID = 6060

    fun canOpenFromBackground(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun homeSettingsIntent(): Intent = Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<PairedDevice> = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
            .map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun launch(context: Context, reason: String) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (canOpenFromBackground(context)) {
            runCatching { context.startActivity(open) }.onSuccess { return }
        }
        // sin permiso: aviso a pantalla completa para abrirla con un toque
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Abrir al conectar con el coche", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, 9, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bulb)
            .setContentTitle("Scala Ambient")
            .setContentText("$reason · toca para abrir")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setColor(0xFFE4002B.toInt())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, n) }
    }
}
