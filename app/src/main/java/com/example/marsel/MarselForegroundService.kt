package com.example.marsel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * ForegroundService unique de Marsel (TODO.md) :
 *
 *  - mode A « alerte en cours » : automatique, de l'appui long sur le
 *    bouton d'alerte jusqu'à la fin de l'alerte. Garde le GPS, le réseau
 *    Marsel, les positions et la relance des 20 min écran éteint. Jamais
 *    coupé pour la batterie. Notification « Alerte Marsel active » avec
 *    l'action « Terminer l'alerte ».
 *  - mode B « relais en arrière-plan » : opt-in dans les Réglages. Le
 *    téléphone relaie les alertes Marsel des personnes à proximité (et
 *    envoie leurs SMS s'il a SIM + réseau) même application fermée.
 *    « Uniquement en charge », pause sous 20 % de batterie (reprise à 25 %
 *    ou au branchement), redémarrage après reboot.
 *
 * Le service ne contient aucune logique réseau : il retient le moteur
 * (MarselEngine) et affiche l'état. Toutes les méthodes tournent sur le
 * thread principal.
 */
class MarselForegroundService : Service() {

    companion object {
        private const val TAG = "MARSEL_FGS"
        const val ACTION_END_ALERT = "com.example.marsel.action.END_ALERT"
        private const val SERVICE_CHANNEL_ID = "marsel_service"
        private const val NOTIFICATION_ID = 1002
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60_000L
        private const val HEARTBEAT_MS = 15 * 60_000L

        // Thread principal uniquement.
        private var instance: MarselForegroundService? = null

        /**
         * Met le service en accord avec l'état courant (alerte, réglages du
         * relais). À appeler sur le thread principal après chaque changement.
         * Ne démarre le service que si une alerte est en cours ou que le relais
         * est activé ; s'il tourne déjà, il se met à jour lui-même.
         */
        fun sync(context: Context) {
            instance?.let { if (!it.stopping) { it.refresh(); return } }
            val app = context.applicationContext
            val alert = MarselEngine.peek()?.let { !it.destroyed && it.isHeld(MarselEngine.REASON_ALERT) } == true
            if (!alert && !RelaySettings.isEnabled(app)) return
            try {
                ContextCompat.startForegroundService(app, Intent(app, MarselForegroundService::class.java))
            } catch (e: Exception) {
                // Android 12+ : démarrage interdit depuis l'arrière-plan hors
                // exemptions (boot, action de l'utilisateur…). L'écran
                // relancera la synchronisation à sa prochaine ouverture.
                Log.w(TAG, "démarrage du service refusé : ${e.message}")
            }
        }

        /** État affiché dans les Réglages (JSON). */
        fun statusJson(context: Context): String {
            val s = instance?.relayState
                ?: if (RelaySettings.isEnabled(context)) null else RelayPolicy.State.OFF
            val name = s?.name ?: "STARTING"
            return """{"enabled":${RelaySettings.isEnabled(context)},""" +
                """"chargingOnly":${RelaySettings.isChargingOnly(context)},""" +
                """"consentAt":${RelaySettings.consentAt(context)},""" +
                """"state":"$name"}"""
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiverRegistered = false
    private var plugged = false
    private var levelPct = -1
    private var lowBatteryLatched = false
    private var relayHeld = false
    private var relayState: RelayPolicy.State = RelayPolicy.State.OFF
    private var foregroundTypes = -1      // -1 : pas encore au premier plan
    private var stopping = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (readBattery(intent)) refresh()
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            // Renouvelle le wake lock (posé avec délai d'expiration : s'il
            // n'est plus renouvelé, il retombe de lui-même).
            refresh()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        try {
            val sticky = ContextCompat.registerReceiver(
                this, batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            batteryReceiverRegistered = true
            sticky?.let { readBattery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "batterie : ${e.message}")
        }
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Une demande de démarrage arrivée pendant un arrêt en cours l'annule.
        stopping = false
        refresh()
        // Relancé par le système après un arrêt du processus : seul le relais
        // reprend (l'alerte, elle, est reprise par l'écran à la réouverture).
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (batteryReceiverRegistered) {
            try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { /* ignore */ }
            batteryReceiverRegistered = false
        }
        releaseWakeLock()
        if (relayHeld) {
            relayHeld = false
            MarselEngine.peek()?.release(MarselEngine.REASON_RELAY)
        }
        super.onDestroy()
    }

    /** @return true si l'état utile (branché, palier de batterie) a changé. */
    private fun readBattery(intent: Intent): Boolean {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val isPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val changed = isPlugged != plugged || bucket(pct) != bucket(levelPct)
        plugged = isPlugged
        levelPct = pct
        return changed
    }

    // Seuls les franchissements de 20 % et 25 % comptent.
    private fun bucket(pct: Int): Int = when {
        pct < 0 -> -1
        pct < RelayPolicy.PAUSE_BELOW_PCT -> 0
        pct < RelayPolicy.RESUME_AT_PCT -> 1
        else -> 2
    }

    fun refresh() {
        if (stopping) return
        val engine = MarselEngine.peek()
        val alert = engine != null && !engine.destroyed && engine.isHeld(MarselEngine.REASON_ALERT)
        val decision = RelayPolicy.decide(
            enabled = RelaySettings.isEnabled(this),
            chargingOnly = RelaySettings.isChargingOnly(this),
            plugged = plugged,
            levelPct = levelPct,
            lowBatteryLatched = lowBatteryLatched
        )
        val previousState = relayState
        lowBatteryLatched = decision.lowBatteryLatched
        relayState = decision.state
        val relayRuns = decision.state == RelayPolicy.State.ACTIVE

        if (relayRuns && !relayHeld) {
            relayHeld = true
            MarselEngine.get(this).hold(MarselEngine.REASON_RELAY)
        } else if (!relayRuns && relayHeld) {
            relayHeld = false
            MarselEngine.peek()?.release(MarselEngine.REASON_RELAY)
        }
        if (previousState != relayState) {
            Log.i(TAG, "relais : $previousState → $relayState (batterie $levelPct %, branché=$plugged)")
        }

        if (!alert && decision.state == RelayPolicy.State.OFF) {
            stopNow()
            return
        }

        val notification = buildNotification(alert, decision.state)
        val types = foregroundTypesFor(alert)
        if (types != foregroundTypes) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
                foregroundTypes = types
            } catch (e: Exception) {
                // Ex. Android 14 : type « location » refusé depuis
                // l'arrière-plan. On garde les types en place.
                Log.w(TAG, "startForeground($types) : ${e.message}")
                if (foregroundTypes == -1) {
                    try {
                        val fallback = connectedDeviceType()
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
                        foregroundTypes = fallback
                    } catch (e2: Exception) {
                        Log.e(TAG, "service de premier plan impossible : ${e2.message}")
                        stopNow()
                        return
                    }
                } else {
                    notifyOnly(notification)
                }
            }
        } else {
            notifyOnly(notification)
        }

        // CPU éveillé pendant l'alerte (positions toutes les 10 s, relance des
        // 20 min) ; pour le relais, seulement branché (aucun coût batterie).
        if (alert || (relayRuns && plugged)) acquireWakeLock() else releaseWakeLock()
    }

    private fun stopNow() {
        stopping = true
        releaseWakeLock()
        try {
            if (foregroundTypes == -1) {
                // Démarré par startForegroundService : Android exige un
                // startForeground() même si l'on s'arrête aussitôt.
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID,
                    buildNotification(false, RelayPolicy.State.ACTIVE), connectedDeviceType()
                )
            }
        } catch (e: Exception) { /* ignore */ }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { /* ignore */ }
        foregroundTypes = -1
        stopSelf()
    }

    private fun notifyOnly(notification: Notification) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notify : ${e.message}")
        }
    }

    private fun connectedDeviceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun foregroundTypesFor(alert: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (alert) {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                MarselEngine.peek()?.isAudioRecording == true &&
                granted(Manifest.permission.RECORD_AUDIO)
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return types
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun buildNotification(alert: Boolean, relay: RelayPolicy.State): Notification {
        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags()
        )
        val builder = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (alert) {
            val endAlert = PendingIntent.getActivity(
                this, 11,
                Intent(this, MainActivity::class.java).apply {
                    action = ACTION_END_ALERT
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                pendingFlags()
            )
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Alerte Marsel active")
                .setContentText("Position et réseau Marsel actifs, même écran éteint.")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, "Terminer l'alerte", endAlert)
        } else {
            val (title, text) = when (relay) {
                RelayPolicy.State.PAUSED_LOW_BATTERY ->
                    "Relais en pause : batterie faible" to
                        "Reprise automatique à 25 % de batterie ou dès le branchement."
                RelayPolicy.State.WAITING_CHARGE ->
                    "Marsel — relais en attente de charge" to
                        "« Uniquement en charge » : le relais démarre dès que le téléphone est branché."
                else ->
                    "Marsel — relais actif" to
                        "Vous aidez les personnes à proximité : leurs alertes Marsel sont relayées."
            }
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "Service Marsel (alerte en cours, relais)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification permanente pendant une alerte en cours ou quand le relais en arrière-plan est activé"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Marsel:service")
                .also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            wl.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock : ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) { /* ignore */ }
    }
}
