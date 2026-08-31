package com.tomasthrawat.wifigamereceiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tomasthrawat.wifigamereceiver.adb.UinputGamepadClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class ReceiverService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var runJob: Job? = null
    private var socket: DatagramSocket? = null
    private var gamepad: UinputGamepadClient? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ReceiverStatus.state.value = ReceiverStatus.State.CONNECTING_ADB
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال المحلي بـ ADB…"))
        if (runJob == null) {
            runJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runLoop() {
        val udpPort = prefs.getInt(KEY_UDP_PORT, DEFAULT_UDP_PORT)
        val adbPort = prefs.getInt(KEY_ADB_PORT, DEFAULT_ADB_PORT)
        val pairPort = prefs.getInt(KEY_PAIR_PORT, 0)
        val pairCode = prefs.getString(KEY_PAIR_CODE, "") ?: ""

        val client = UinputGamepadClient(applicationContext)
        gamepad = client

        var connected = false
        var attempt = 0
        while (!connected && isActiveService()) {
            attempt++
            try {
                if (pairCode.isNotEmpty() && pairPort > 0 && attempt == 1) {
                    client.pair(LOOPBACK, pairPort, pairCode)
                }
                client.connect(LOOPBACK, adbPort)
                connected = true
                Log.i(TAG, "local adb connect ok (attempt $attempt)")
            } catch (e: Exception) {
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "local adb connect failed (attempt $attempt): $msg")
                ReceiverStatus.lastError.value = msg
                updateNotification("فشل الاتصال المحلي بـ ADB — إعادة محاولة ($attempt)")
                delay(minOf(2000L + attempt * 500L, 8000L))
            }
        }
        if (!connected) return

        ReceiverStatus.state.value = ReceiverStatus.State.CONNECTED
        updateNotification("متصل — بانتظار أوامر الموبايل على UDP:$udpPort")

        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(udpPort))
        }
        socket = s

        val buf = ByteArray(512)
        while (isActiveService()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                handleMessage(String(packet.data, 0, packet.length, Charsets.UTF_8))
            } catch (e: Exception) {
                if (isActiveService()) Log.w(TAG, "udp receive error: ${e.message}")
            }
        }
    }

    private fun handleMessage(msg: String) {
        val g = gamepad ?: return
        when {
            msg.startsWith("JOY:") -> {
                val parts = msg.removePrefix("JOY:").split(",")
                val x = parts.getOrNull(0)?.toFloatOrNull() ?: return
                val y = parts.getOrNull(1)?.toFloatOrNull() ?: return
                g.sendStick(x, y)
            }
            msg.contains(":") -> {
                val (code, state) = msg.split(":", limit = 2)
                g.sendButton(code, state == "1")
            }
        }
    }

    private fun isActiveService() = ReceiverStatus.state.value != ReceiverStatus.State.STOPPED

    override fun onDestroy() {
        ReceiverStatus.state.value = ReceiverStatus.State.STOPPED
        runJob?.cancel()
        runJob = null
        socket?.close()
        val g = gamepad
        scope.launch { g?.disconnect() }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "WifiGameController Receiver", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WifiGameController")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL_ID = "receiver_channel"
        private const val NOTIF_ID = 1
        private const val LOOPBACK = "127.0.0.1"

        const val PREFS = "receiver_prefs"
        const val KEY_UDP_PORT = "udp_port"
        const val KEY_ADB_PORT = "adb_port"
        const val KEY_PAIR_PORT = "pair_port"
        const val KEY_PAIR_CODE = "pair_code"
        const val DEFAULT_UDP_PORT = 8766
        const val DEFAULT_ADB_PORT = 5555
    }
}
