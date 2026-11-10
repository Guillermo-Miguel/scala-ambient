package es.scala.ambient

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class Link(val label: String) {
    NO_PERMISSION("Falta el permiso de Bluetooth"),
    BLUETOOTH_OFF("Bluetooth desactivado"),
    SEARCHING("Sin centralita · buscando"),
    CONNECTING("Conectando…"),
    CONNECTED("Conectado"),
}

/**
 * Conexión Bluetooth LE con el controlador ESP32. Una sola instancia para la app del móvil y Android Auto.
 * Las órdenes se envían en orden, de una en una (BLE no admite dos escrituras a la vez).
 */
@SuppressLint("MissingPermission")
class AmbientBle(private val context: Context) {

    companion object {
        val SERVICE: UUID = UUID.fromString("5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0001")
        val CMD: UUID = UUID.fromString("5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0002")
        val STATE: UUID = UUID.fromString("5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0003")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _link = MutableStateFlow(Link.SEARCHING)
    val link: StateFlow<Link> = _link

    // Los ajustes se guardan en el móvil: se puede usar todo sin la centralita y se le envían al conectarla.
    private val saved = context.getSharedPreferences("ambient_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(AmbientState.parse(saved.getString("state", "") ?: "") ?: AmbientState())
    val state: StateFlow<AmbientState> = _state

    /** Hay cambios hechos sin centralita que aún no ha recibido. */
    private var pending: Boolean = saved.getBoolean("pending", true)
        set(v) { field = v; saved.edit().putBoolean("pending", v).apply() }

    val connected: Boolean get() = _link.value == Link.CONNECTED

    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var started = false

    private val queue = Channel<String>(Channel.UNLIMITED)
    private var pendingWrite: CompletableDeferred<Unit>? = null

    init {
        scope.launch { _state.collect { saved.edit().putString("state", it.encode()).apply() } }
        scope.launch {
            for (cmd in queue) {
                val g = gatt ?: continue
                val c = cmdChar ?: continue
                val done = CompletableDeferred<Unit>()
                pendingWrite = done
                val bytes = cmd.toByteArray()
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.value = bytes
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (ok) withTimeoutOrNull(1500) { done.await() }
                pendingWrite = null
            }
        }
    }

    fun hasPermission(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Llamar al abrir la app o al iniciar Android Auto. Se puede llamar varias veces. */
    fun start() {
        if (!hasPermission()) { _link.value = Link.NO_PERMISSION; return }
        val a = adapter
        if (a == null || !a.isEnabled) { _link.value = Link.BLUETOOTH_OFF; retryLater(); return }
        if (started && gatt != null) return
        started = true
        scan()
    }

    // ---------- Optimistic updates: la interfaz responde al momento y el ESP32 confirma después ----------

    fun send(cmd: String) {
        if (!connected) pending = true
        _state.value = predict(_state.value, cmd)
        queue.trySend(cmd)
    }

    private var brightJob: kotlinx.coroutines.Job? = null
    /** Para el deslizador: envía como mucho ~12 órdenes por segundo. */
    fun sendBrightness(mask: Int, value: Int) {
        if (!connected) pending = true
        _state.value = predict(_state.value, Cmd.bright(mask, value))
        brightJob?.cancel()
        brightJob = scope.launch { delay(80); queue.trySend(Cmd.bright(mask, value)) }
    }

    private fun predict(s: AmbientState, cmd: String): AmbientState {
        val p = cmd.split(" ")
        fun each(mask: Int, f: (ZoneState) -> ZoneState) =
            s.zones.mapIndexed { i, z -> if (mask and (1 shl i) != 0) f(z) else z }
        return when (p[0].firstOrNull()) {
            'P' -> s.copy(on = when (cmd) { "P1" -> true; "P0" -> false; else -> !s.on })
            'C' -> s.copy(on = true, scene = null, zones = each(p[1].toInt()) { it.copy(rgb = p[2].toInt(16)) })
            'B' -> s.copy(on = true, scene = null, zones = each(p[1].toInt()) { it.copy(bright = p[2].toInt()) })
            'F' -> s.copy(effect = Effect.of(p[1].toInt()))
            'V' -> s.copy(speed = p[1].toInt().coerceIn(1, 10))
            'O' -> s.copy(welcomeOnDoor = cmd == "O1")
            'S' -> Scene.of(p[1].toInt())?.let { sceneState(s, it) } ?: s
            else -> s
        }
    }

    private fun sceneState(s: AmbientState, sc: Scene): AmbientState {
        // Igual que SCENES en el firmware: salpicadero, puertas delanteras, puertas traseras
        val (d, f, r) = when (sc) {
            Scene.FIRMA -> Triple(ZoneState(0xF2F4F7, 70), ZoneState(0xE4002B, 60), ZoneState(0xE4002B, 50))
            Scene.CONFORT -> Triple(ZoneState(0xFFC98A, 62), ZoneState(0xFFC98A, 55), ZoneState(0xFFC98A, 45))
            Scene.SPORT -> Triple(ZoneState(0xE4002B, 92), ZoneState(0xE4002B, 85), ZoneState(0xE4002B, 75))
            Scene.NOCHE -> Triple(ZoneState(0xFF9F0A, 16), ZoneState(0xFF9F0A, 12), ZoneState(0xFF9F0A, 10))
        }
        return s.copy(on = true, scene = sc, zones = listOf(d, f, f, r, r))
    }

    // ---------- Búsqueda y conexión ----------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connect(result.device)
        }
        override fun onScanFailed(errorCode: Int) { stopScan(); retryLater() }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return retryLater()
        if (scanning) return
        _link.value = Link.SEARCHING
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        main.postDelayed({ if (scanning) { stopScan(); retryLater() } }, 12_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    private fun retryLater() {
        main.postDelayed({ started = false; start() }, 5_000)
    }

    private fun connect(device: BluetoothDevice) {
        _link.value = Link.CONNECTING
        gatt = device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(64)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    gatt = null; cmdChar = null
                    _link.value = Link.SEARCHING
                    retryLater()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val svc = g.getService(SERVICE) ?: return@post g.disconnect()
                cmdChar = svc.getCharacteristic(CMD)
                val st = svc.getCharacteristic(STATE)
                if (st != null) {
                    g.setCharacteristicNotification(st, true)
                    val d = st.getDescriptor(CCCD)
                    if (d == null) syncOnConnect(g) else {
                        if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(d)
                        }
                    }
                } else syncOnConnect(g)
                _link.value = Link.CONNECTED
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { syncOnConnect(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { pendingWrite?.complete(Unit) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onValue(value)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            onValue(value)

        // Android 10–12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") c.value?.let { onValue(it) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") c.value?.let { onValue(it) }
        }
    }

    /** Al conectar: si configuraste las luces sin la centralita, se le envía tu configuración; si no, se lee la suya. */
    private fun syncOnConnect(g: BluetoothGatt) {
        if (pending) {
            pending = false
            _state.value.commands().forEach { queue.trySend(it) }
        } else {
            g.getService(SERVICE)?.getCharacteristic(STATE)?.let { g.readCharacteristic(it) }
        }
    }

    private fun onValue(bytes: ByteArray) {
        val parsed = AmbientState.parse(String(bytes)) ?: return
        main.post { _state.value = parsed }
    }
}
