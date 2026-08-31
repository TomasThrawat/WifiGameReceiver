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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections

class ReceiverService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var runJob: Job? = null
    private var beaconJob: Job? = null
    private var socket: DatagramSocket? = null
    private var beaconSocket: DatagramSocket? = null
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

        if (beaconJob == null) {
            beaconJob = scope.launch { beaconLoop(udpPort) }
        }

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

    /**
     * Broadcasts "WGCTV1|<name>|<ip>|<controlPort>" to 255.255.255.255:BEACON_PORT once a
     * second while connected, so WifiGameController's discovery screen can list this TV
     * without the user typing an IP/port by hand.
     */
    private suspend fun beaconLoop(controlPort: Int) {
        val bSocket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (e: Exception) {
            Log.w(TAG, "beacon socket open failed: ${e.message}")
            return
        }
        beaconSocket = bSocket
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "WifiGameReceiver"
        val broadcastAddr = InetAddress.getByName("255.255.255.255")
        while (isActiveService()) {
            try {
                val ip = localIpAddress()
                if (ip != null) {
                    val msg = "WGCTV1|$deviceName|$ip|$controlPort"
                    val data = msg.toByteArray(Charsets.UTF_8)
                    bSocket.send(DatagramPacket(data, data.size, broadcastAddr, BEACON_PORT))
                }
            } catch (e: Exception) {
                Log.w(TAG, "beacon send failed: ${e.message}")
            }
            delay(1000)
        }
    }

    /** First non-loopback IPv4 address — what goes in the beacon and is shown on-screen. */
    private fun localIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
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
        beaconJob?.cancel()
        beaconJob = null
        socket?.close()
        beaconSocket?.close()
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
        const val BEACON_PORT = 8767

        const val PREFS = "receiver_prefs"
        const val KEY_UDP_PORT = "udp_port"
        const val KEY_ADB_PORT = "adb_port"
        const val KEY_PAIR_PORT = "pair_port"
        const val KEY_PAIR_CODE = "pair_code"
        const val DEFAULT_UDP_PORT = 8766
        const val DEFAULT_ADB_PORT = 5555
    }
}
