package es.scala.ambient.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CallPhase { RINGING, DIALING, ACTIVE, HOLDING, ENDED, PICK_SIM }

data class SimChoice(val label: String, val handle: PhoneAccountHandle)

/** Lo que la pantalla de llamada necesita saber. */
data class CallUi(
    val number: String,
    val name: String?,
    val phase: CallPhase,
    val incoming: Boolean,
    val connectedAt: Long,          // SystemClock.elapsedRealtime() al descolgar; 0 si aún no
    val muted: Boolean,
    val speaker: Boolean,
    val sims: List<SimChoice> = emptyList(),
    /** Llamada que lleva el sistema (en Android Auto): solo se puede contestar y colgar. */
    val external: Boolean = false,
    val hint: String? = null,
) {
    val title: String get() = name ?: number.ifBlank { "Número oculto" }
}

/**
 * Llamadas gestionadas por la propia app.
 * Android solo lo permite a la app de teléfono predeterminada: si no lo es, se usa la del sistema.
 */
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _ui = MutableStateFlow<CallUi?>(null)
    val ui: StateFlow<CallUi?> = _ui

    /** Número que otra app pidió marcar (abre Teléfono con él puesto). */
    val dialRequest = MutableStateFlow<String?>(null)

    internal var service: CallService? = null
    private var call: Call? = null
    private var incoming = false
    private var connectedAt = 0L
    private var name: String? = null
    private var muted = false
    private var route = CallAudioState.ROUTE_EARPIECE
    private var supportedRoutes = CallAudioState.ROUTE_EARPIECE

    // ---------- ¿Somos la app de teléfono? ----------

    fun isDefaultDialer(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true

    fun requestDefaultDialerIntent(context: Context): Intent? =
        context.getSystemService(RoleManager::class.java)
            ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_DIALER) }
            ?.createRequestRoleIntent(RoleManager.ROLE_DIALER)

    /** Llamar. Si somos la app de teléfono, la llamada se queda dentro de la app. */
    @SuppressLint("MissingPermission")
    fun place(context: Context, number: String) {
        if (number.isBlank()) return
        lastDialed = number
        lastDialedAt = SystemClock.elapsedRealtime()
        val uri = Uri.fromParts("tel", number, null)
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        if (canCall && isDefaultDialer(context)) {
            val tm = context.getSystemService(TelecomManager::class.java)
            runCatching { tm.placeCall(uri, Bundle()) }.onSuccess { return }
        }
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- Llamadas que nos pasa Android (CallService) ----------

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) {
            if (state == Call.STATE_ACTIVE && connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
            publish()
        }
        override fun onDetailsChanged(c: Call, details: Call.Details) = publish()
    }

    internal fun added(context: Context, c: Call) {
        call?.unregisterCallback(callback)
        call = c
        c.registerCallback(callback)
        incoming = stateOf(c) == Call.STATE_RINGING
        connectedAt = if (stateOf(c) == Call.STATE_ACTIVE) SystemClock.elapsedRealtime() else 0L
        muted = false
        name = null
        publish()
        val number = numberOf(c)
        if (number.isNotBlank()) scope.launch {
            name = withContext(Dispatchers.IO) { lookupName(context, number) }
            publish()
        }
    }

    internal fun removed(c: Call) {
        if (c != call) return
        c.unregisterCallback(callback)
        _ui.value = _ui.value?.copy(phase = CallPhase.ENDED)
        call = null
        scope.launch {
            delay(1500)
            if (call == null) _ui.value = null
        }
    }

    internal fun audioChanged(isMuted: Boolean, r: Int, supported: Int) {
        muted = isMuted
        route = r
        supportedRoutes = supported
        publish()
    }

    @Suppress("DEPRECATION")
    private fun stateOf(c: Call): Int = c.state

    private fun numberOf(c: Call): String = c.details?.handle?.schemeSpecificPart ?: ""

    private fun publish() {
        val c = call ?: return
        val st = stateOf(c)
        val phase = when (st) {
            Call.STATE_RINGING, Call.STATE_NEW -> CallPhase.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallPhase.DIALING
            Call.STATE_ACTIVE -> CallPhase.ACTIVE
            Call.STATE_HOLDING -> CallPhase.HOLDING
            Call.STATE_SELECT_PHONE_ACCOUNT -> CallPhase.PICK_SIM
            else -> CallPhase.ENDED
        }
        _ui.value = CallUi(
            number = numberOf(c),
            name = name,
            phase = phase,
            incoming = incoming,
            connectedAt = connectedAt,
            muted = muted,
            speaker = route == CallAudioState.ROUTE_SPEAKER,
            sims = if (phase == CallPhase.PICK_SIM) simsFor(c) else emptyList(),
        )
        service?.updateNotification(_ui.value)
    }

    @Suppress("DEPRECATION")
    private fun simsFor(c: Call): List<SimChoice> {
        val handles: List<PhoneAccountHandle> =
            c.details?.intentExtras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
        val tm = service?.getSystemService(TelecomManager::class.java)
        return handles.mapIndexed { i, h ->
            val label = runCatching { tm?.getPhoneAccount(h)?.label?.toString() }.getOrNull()
            SimChoice(label?.ifBlank { null } ?: "SIM ${i + 1}", h)
        }
    }

    private fun lookupName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        }.getOrNull()
    }

    // ---------- Llamadas que lleva el sistema (Android Auto) ----------
    // Con el móvil conectado al coche, Android entrega las llamadas a Android Auto y no a esta app.
    // Se detectan por el estado del audio del teléfono y se cuelgan o contestan a través del sistema.

    private var lastDialed: String? = null
    private var lastDialedAt = 0L
    private var watchJob: kotlinx.coroutines.Job? = null
    private var appContext: Context? = null

    fun watchSystemCalls(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (watchJob?.isActive == true) return
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        watchJob = scope.launch {
            var last = android.media.AudioManager.MODE_NORMAL
            while (true) {
                val mode = runCatching { audio.mode }.getOrDefault(android.media.AudioManager.MODE_NORMAL)
                if (call == null && mode != last) onSystemMode(app, mode)
                last = mode
                delay(400)
            }
        }
    }

    fun stopWatchingSystemCalls() {
        watchJob?.cancel(); watchJob = null
        if (_ui.value?.external == true) _ui.value = null
    }

    private fun onSystemMode(context: Context, mode: Int) {
        when (mode) {
            android.media.AudioManager.MODE_RINGTONE -> {
                _ui.value = CallUi(
                    number = "", name = "Llamada entrante", phase = CallPhase.RINGING, incoming = true,
                    connectedAt = 0L, muted = false, speaker = false, external = true,
                )
            }
            android.media.AudioManager.MODE_IN_CALL -> {
                val prev = _ui.value?.takeIf { it.external }
                val mine = lastDialed?.takeIf { SystemClock.elapsedRealtime() - lastDialedAt < 60_000 }
                val number = if (prev?.incoming == true) "" else mine ?: ""
                _ui.value = CallUi(
                    number = number,
                    name = if (prev?.incoming == true) "Llamada" else if (number.isBlank()) "Llamada" else null,
                    phase = CallPhase.ACTIVE, incoming = prev?.incoming == true,
                    connectedAt = if (prev?.incoming == true) SystemClock.elapsedRealtime() else 0L,
                    muted = false, speaker = false, external = true,
                )
                if (number.isNotBlank()) scope.launch {
                    val n = withContext(Dispatchers.IO) { lookupName(context, number) }
                    val cur = _ui.value
                    if (n != null && cur != null && cur.external && cur.number == number) _ui.value = cur.copy(name = n)
                }
            }
            else -> {
                val cur = _ui.value?.takeIf { it.external } ?: return
                _ui.value = cur.copy(phase = CallPhase.ENDED, hint = null)
                scope.launch {
                    delay(1500)
                    if (_ui.value?.external == true && _ui.value?.phase == CallPhase.ENDED) _ui.value = null
                }
            }
        }
    }

    /** Contestar o colgar a través del sistema (permiso «Teléfono»). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun system(accept: Boolean) {
        val ctx = appContext ?: return
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        val done = ok && runCatching {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (accept) { tm.acceptRingingCall(); true } else tm.endCall()
        }.getOrDefault(false)
        if (!done) _ui.value = _ui.value?.copy(
            hint = if (ok) "No se ha podido. Usa el botón del volante." else "Abre Scala Ambient en el móvil una vez para permitir colgar desde aquí.",
        )
    }

    // ---------- Mandos ----------

    fun answer() {
        if (_ui.value?.external == true) { system(accept = true); return }
        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        if (_ui.value?.external == true) { system(accept = false); return }
        call?.reject(false, null)
    }

    fun hangUp() {
        if (_ui.value?.external == true) { system(accept = false); return }
        val c = call ?: return
        if (stateOf(c) == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }

    fun toggleMute() { service?.setMuted(!muted) }

    @Suppress("DEPRECATION")
    fun toggleSpeaker() {
        val s = service ?: return
        val target = when {
            route == CallAudioState.ROUTE_SPEAKER && supportedRoutes and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
            route == CallAudioState.ROUTE_SPEAKER && supportedRoutes and CallAudioState.ROUTE_WIRED_HEADSET != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
            route == CallAudioState.ROUTE_SPEAKER -> CallAudioState.ROUTE_EARPIECE
            else -> CallAudioState.ROUTE_SPEAKER
        }
        s.setAudioRoute(target)
    }

    fun tone(c: Char) {
        val call = call ?: return
        call.playDtmfTone(c)
        scope.launch { delay(160); call.stopDtmfTone() }
    }

    fun chooseSim(choice: SimChoice) { call?.phoneAccountSelected(choice.handle, false) }
}
