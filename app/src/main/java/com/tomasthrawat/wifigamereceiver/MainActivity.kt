package com.tomasthrawat.wifigamereceiver

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(ReceiverService.PREFS, Context.MODE_PRIVATE)

        val ipText = findViewById<TextView>(R.id.ipText)
        val statusText = findViewById<TextView>(R.id.statusText)
        val udpPortInput = findViewById<EditText>(R.id.udpPortInput)
        val adbPortInput = findViewById<EditText>(R.id.adbPortInput)
        val pairPortInput = findViewById<EditText>(R.id.pairPortInput)
        val pairCodeInput = findViewById<EditText>(R.id.pairCodeInput)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        ipText.text = getString(R.string.tv_ip_label, localIpAddress() ?: "—")

        udpPortInput.setText(prefs.getInt(ReceiverService.KEY_UDP_PORT, ReceiverService.DEFAULT_UDP_PORT).toString())
        adbPortInput.setText(prefs.getInt(ReceiverService.KEY_ADB_PORT, ReceiverService.DEFAULT_ADB_PORT).toString())
        prefs.getInt(ReceiverService.KEY_PAIR_PORT, 0).let { if (it > 0) pairPortInput.setText(it.toString()) }

        startButton.setOnClickListener {
            prefs.edit()
                .putInt(ReceiverService.KEY_UDP_PORT, udpPortInput.text.toString().toIntOrNull() ?: ReceiverService.DEFAULT_UDP_PORT)
                .putInt(ReceiverService.KEY_ADB_PORT, adbPortInput.text.toString().toIntOrNull() ?: ReceiverService.DEFAULT_ADB_PORT)
                .putInt(ReceiverService.KEY_PAIR_PORT, pairPortInput.text.toString().toIntOrNull() ?: 0)
                .putString(ReceiverService.KEY_PAIR_CODE, pairCodeInput.text.toString().trim())
                .apply()
            ContextCompat.startForegroundService(this, Intent(this, ReceiverService::class.java))
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, ReceiverService::class.java))
        }

        lifecycleScope.launch {
            ReceiverStatus.state.collect { state ->
                statusText.text = when (state) {
                    ReceiverStatus.State.STOPPED -> getString(R.string.status_stopped)
                    ReceiverStatus.State.CONNECTING_ADB -> getString(R.string.status_starting)
                    ReceiverStatus.State.CONNECTED -> getString(R.string.status_connected)
                }
            }
        }
    }

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
}
