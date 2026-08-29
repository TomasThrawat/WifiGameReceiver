package com.tomasthrawat.wifigamereceiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * Foreground service: owns the UDP socket, parses the WifiGameController
 * text protocol, and forwards each event to the bound Shizuku UserService
 * (GamepadBridge.service) which does the actual uinput injection.
 */
class UdpReceiverService : Service() {

    companion object {
        const val EXTRA_PORT = "extra_port"
        private const val CHANNEL_ID = "wifigamereceiver_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var socket: DatagramSocket? = null
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Combined D-pad state — LEFT/RIGHT/UP/DOWN messages update one axis each,
    // but uinput needs the combined hat value on every change.
    private var dpadX = 0
    private var dpadY = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        startForeground(NOTIFICATION_ID, buildNotification(port))
        if (port in 1024..65535) startListening(port)
        return START_STICKY
    }

    private fun startListening(port: Int) {
        scope.launch {
            try {
                val s = DatagramSocket(port)
                socket = s
                val buffer = ByteArray(64)
                while (socketOpen()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    s.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    handleMessage(message)
                }
            } catch (_: Exception) {
                // socket closed on stop(), or bind failure (e.g. port already in use)
            }
        }
    }

    private fun socketOpen() = socket != null && !socket!!.isClosed

    private fun handleMessage(message: String) {
        val svc = GamepadBridge.service ?: return
        try {
            if (message.startsWith("JOY:")) {
                val parts = message.removePrefix("JOY:").split(",")
                if (parts.size == 2) {
                    val fx = parts[0].toFloatOrNull() ?: return
                    val fy = parts[1].toFloatOrNull() ?: return
                    svc.sendJoystick((fx * 255f).toInt(), (fy * 255f).toInt())
                }
                return
            }
            val parts = message.split(":")
            if (parts.size != 2) return
            val down = parts[1] == "1"
            when (parts[0]) {
                "A" -> svc.sendButton(UinputProtocol.BTN_A, down)
                "B" -> svc.sendButton(UinputProtocol.BTN_B, down)
                "X" -> svc.sendButton(UinputProtocol.BTN_X, down)
                "Y" -> svc.sendButton(UinputProtocol.BTN_Y, down)
                "START" -> svc.sendButton(UinputProtocol.BTN_START, down)
                "SELECT" -> svc.sendButton(UinputProtocol.BTN_SELECT, down)
                "LEFT" -> { dpadX = if (down) -1 else if (dpadX == -1) 0 else dpadX; svc.sendDpad(dpadX, dpadY) }
                "RIGHT" -> { dpadX = if (down) 1 else if (dpadX == 1) 0 else dpadX; svc.sendDpad(dpadX, dpadY) }
                "UP" -> { dpadY = if (down) -1 else if (dpadY == -1) 0 else dpadY; svc.sendDpad(dpadX, dpadY) }
                "DOWN" -> { dpadY = if (down) 1 else if (dpadY == 1) 0 else dpadY; svc.sendDpad(dpadX, dpadY) }
            }
        } catch (_: Exception) {
            // AIDL call can throw RemoteException if the UserService process died mid-session
        }
    }

    override fun onDestroy() {
        socket?.close()
        socket = null
        scope.cancel()
        job.cancel()
        GamepadBridge.unbind()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(port: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, port))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
}
