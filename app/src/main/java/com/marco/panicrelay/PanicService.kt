package com.marco.panicrelay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.UUID

/**
 * Persistent foreground service that keeps a BLE link to the pepper spray and
 * fires the alert when the device signals an activation on characteristic 0xFFC3.
 *
 * v0.2 changes: faster reconnect, HIGH connection priority, RSSI keep-alive to
 * shrink the blind window between the device's periodic disconnects (status 8).
 */
class PanicService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var alerts: AlertManager
    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var notifyEnabledAt = 0L
    private var lastFireAt = 0L
    private var wantConnected = true
    private var foregrounded = false

    private val keepAlive = object : Runnable {
        override fun run() {
            try { gatt?.readRemoteRssi() } catch (_: Exception) {}
            if (wantConnected) main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        alerts = AlertManager(this)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If we could not go foreground (e.g. blocked right after boot), bail out
        // cleanly instead of running half-initialized.
        if (!foregrounded) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_TEST) {
            alerts.fire("TEST")
            return START_STICKY
        }
        wantConnected = true
        prefs.serviceEnabled = true
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wantConnected = false
        main.removeCallbacks(keepAlive)
        closeGatt()
        super.onDestroy()
    }

    // ---- Foreground notification ----------------------------------------
    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(SVC_CH, "PanicRelay attivo",
                NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, SVC_CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("PanicRelay in ascolto")
            .setContentText("In attesa del segnale dallo spray")
            .setOngoing(true)
            .setContentIntent(tap)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    SVC_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(SVC_ID, n)
            }
            foregrounded = true
        } catch (e: Exception) {
            // Android can forbid promoting a service to foreground from the
            // background (typically right after boot). Don't crash: leave a
            // tap-to-start notification and stop quietly.
            alerts.log("startForeground blocked: ${e.message}")
            try {
                val prompt = NotificationCompat.Builder(this, SVC_CH)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("PanicRelay: tocca per riattivare")
                    .setContentText("Dopo il riavvio, apri l'app per riattivare la protezione")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
                nm.notify(SVC_ID + 1, prompt)
            } catch (_: Exception) {}
            foregrounded = false
            stopSelf()
        }
    }

    private fun updateStatus(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, SVC_CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("PanicRelay in ascolto")
            .setContentText(text)
            .setOngoing(true)
            .build()
        nm.notify(SVC_ID, n)
    }

    // ---- BLE connection --------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun connect() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = mgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            alerts.log("BT off, retry soon")
            updateStatus("Bluetooth spento - riprovo")
            scheduleReconnect()
            return
        }
        val mac = prefs.deviceMac
        val device = try { adapter.getRemoteDevice(mac) } catch (e: Exception) {
            alerts.log("Bad MAC $mac: ${e.message}"); return
        }
        closeGatt()
        alerts.log("Connecting to $mac")
        updateStatus("Connessione a $mac...")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(this, true, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(this, true, cb)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        main.removeCallbacks(keepAlive)
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        main.removeCallbacks(keepAlive)
        main.postDelayed({ if (wantConnected) connect() }, RECONNECT_MS)
    }

    private val cb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                alerts.log("Connected, discovering services")
                updateStatus("Connesso - leggo i servizi")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                alerts.log("Disconnected (status $status)")
                updateStatus("Disconnesso - riprovo")
                closeGatt()
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE_UUID)
            val ch = svc?.getCharacteristic(NOTIFY_UUID)
            if (ch == null) {
                alerts.log("Char 0xFFC3 not found")
                updateStatus("Characteristic trigger non trovata")
                return
            }
            // Keep the link as responsive as possible.
            try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}

            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
            notifyEnabledAt = System.currentTimeMillis()
            alerts.log("Notifications enabled on 0xFFC3")
            updateStatus("In ascolto del segnale")

            // Start keep-alive: periodic RSSI read keeps traffic flowing and
            // surfaces a dead link quickly.
            main.removeCallbacks(keepAlive)
            main.postDelayed(keepAlive, KEEPALIVE_MS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) = handleNotification(ch, value)

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotification(ch, ch.value ?: ByteArray(0))
        }
    }

    /**
     * Notifications on 0xFFC3 only occur when the device is operated, so any
     * notification (after a short startup grace) counts as an activation. A
     * debounce collapses the TEST lever's on/off toggling into a single alert.
     *
     * Confirmed on-device: real activation arrives as 00-00 (siren ON).
     */
    private fun handleNotification(ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (ch.uuid != NOTIFY_UUID) return
        val hex = value.joinToString("-") { "%02X".format(it) }
        val now = System.currentTimeMillis()

        if (now - notifyEnabledAt < STARTUP_GRACE_MS) {
            alerts.log("Baseline notification ignored: $hex")
            return
        }
        if (now - lastFireAt < DEBOUNCE_MS) {
            alerts.log("Notification within debounce ignored: $hex")
            return
        }
        lastFireAt = now
        alerts.log("ACTIVATION detected: $hex")
        main.post { alerts.fire(hex) }
    }

    companion object {
        const val ACTION_TEST = "com.marco.panicrelay.TEST"
        private const val SVC_CH = "guard"
        private const val SVC_ID = 7

        private const val STARTUP_GRACE_MS = 4_000L
        private const val DEBOUNCE_MS = 8_000L
        private const val RECONNECT_MS = 2_500L
        private const val KEEPALIVE_MS = 3_000L

        val SERVICE_UUID: UUID = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000ffc3-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(ctx: Context) {
            val i = Intent(ctx, PanicService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            Prefs(ctx).serviceEnabled = false
            ctx.stopService(Intent(ctx, PanicService::class.java))
        }

        fun test(ctx: Context) {
            val i = Intent(ctx, PanicService::class.java).setAction(ACTION_TEST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
